/*
 * Copyright 2026 The Sokkuri Authors and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//! Parsing: the acceptance rules V1 to V11 of docs/design/sok-rs.md §4.1,
//! which must accept exactly the files the runtime decoder accepts, and a
//! zero-copy view of an accepted file.

use std::error::Error;
use std::fmt;
use std::iter::Take;

use crate::layout::{CANDIDATE_HEADER_BYTES, HEADER_BYTES, Header, MAGIC, VERSION, u16_at, u32_at};
use crate::utf8;

/// An acceptance rule of the format (docs/design/sok-rs.md §4.1).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum Rule {
    /// V1: the file holds the whole 32-byte header.
    Header,
    /// V2: the magic is `SOK1`.
    Magic,
    /// V3: the format version is 1.
    Version,
    /// V4: the flags are zero.
    Flags,
    /// V5: `maxKeyLength` does not exceed `keyBlobBytes`.
    MaxKeyLength,
    /// V6: the size implied by the header equals the file size.
    Size,
    /// V7: the key offsets increase strictly and end at `keyBlobBytes`.
    KeyOffsets,
    /// V8: every key is well-formed UTF-8 on its own.
    KeyUtf8,
    /// V9: the value offsets increase strictly and end at `valueBlobBytes`.
    ValueOffsets,
    /// V10: every entry has at least one candidate.
    ValueCounts,
    /// V11: the candidates of every entry tile its value region exactly.
    ValueTiling,
}

impl Rule {
    /// Every rule, in the order the reader checks them.
    pub const ALL: [Self; 11] = [
        Self::Header,
        Self::Magic,
        Self::Version,
        Self::Flags,
        Self::MaxKeyLength,
        Self::Size,
        Self::KeyOffsets,
        Self::KeyUtf8,
        Self::ValueOffsets,
        Self::ValueCounts,
        Self::ValueTiling,
    ];

    /// The rule's stable identifier, `V1` to `V11`.
    #[must_use]
    pub const fn id(self) -> &'static str {
        match self {
            Self::Header => "V1",
            Self::Magic => "V2",
            Self::Version => "V3",
            Self::Flags => "V4",
            Self::MaxKeyLength => "V5",
            Self::Size => "V6",
            Self::KeyOffsets => "V7",
            Self::KeyUtf8 => "V8",
            Self::ValueOffsets => "V9",
            Self::ValueCounts => "V10",
            Self::ValueTiling => "V11",
        }
    }
}

/// Why a file is not an acceptable `.sok`: the first rule it breaks.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Violation {
    rule: Rule,
    offset: usize,
    detail: String,
}

impl Violation {
    fn new(rule: Rule, offset: usize, detail: impl Into<String>) -> Self {
        Self {
            rule,
            offset,
            detail: detail.into(),
        }
    }

    /// The rule the file breaks.
    #[must_use]
    pub const fn rule(&self) -> Rule {
        self.rule
    }

    /// The byte offset of the offending field or region.
    #[must_use]
    pub const fn offset(&self) -> usize {
        self.offset
    }

    /// What exactly is wrong, for people.
    #[must_use]
    pub fn detail(&self) -> &str {
        &self.detail
    }
}

impl fmt::Display for Violation {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{} at byte {}: {}", self.rule.id(), self.offset, self.detail)
    }
}

impl Error for Violation {}

/// An accepted `.sok` file: a view of its bytes, which it never copies.
///
/// Every accessor reads the original bytes. Parsing has proven every region
/// in bounds, so accessors that return `Option` return `Some` for every index
/// below [`SokFile::len`]; they return `Option` so that no input can make this
/// crate panic.
#[derive(Debug, Clone, Copy)]
pub struct SokFile<'a> {
    bytes: &'a [u8],
    header: Header,
    entries: usize,
    value_offsets_at: usize,
    value_counts_at: usize,
    key_blob_at: usize,
    value_blob_at: usize,
}

impl<'a> SokFile<'a> {
    /// Checks `bytes` against V1 to V11 and returns a view of it, or the first
    /// rule it breaks.
    ///
    /// # Errors
    ///
    /// A [`Violation`] naming the rule, the byte offset and the detail.
    pub fn parse(bytes: &'a [u8]) -> Result<Self, Violation> {
        // Reading the header is the length check: it fails exactly when the
        // file is shorter than the 32 bytes the header occupies.
        let header = Header::read(bytes).ok_or_else(|| {
            let detail = format!(
                "the file is {} bytes, shorter than the {HEADER_BYTES}-byte header",
                bytes.len()
            );
            Violation::new(Rule::Header, 0, detail)
        })?;
        if bytes.get(..MAGIC.len()) != Some(&MAGIC[..]) {
            return Err(Violation::new(Rule::Magic, 0, "the magic is not SOK1"));
        }
        if header.version != VERSION {
            let detail = format!("formatVersion is {}, not {VERSION}", header.version);
            return Err(Violation::new(Rule::Version, crate::layout::VERSION_AT, detail));
        }
        if header.flags != 0 {
            let detail = format!("flags are {}, not 0", header.flags);
            return Err(Violation::new(Rule::Flags, crate::layout::FLAGS_AT, detail));
        }
        if header.max_key_length > header.key_blob_bytes {
            let detail = format!(
                "maxKeyLength {} exceeds keyBlobBytes {}: a key has at least one UTF-16 unit per byte",
                header.max_key_length, header.key_blob_bytes,
            );
            return Err(Violation::new(
                Rule::MaxKeyLength,
                crate::layout::MAX_KEY_LENGTH_AT,
                detail,
            ));
        }

        // The header's sizes imply the file size; u64 arithmetic cannot
        // overflow on any u32 inputs. Once they agree, every region below
        // lies inside `bytes`, so the usize arithmetic that follows is safe.
        let n = u64::from(header.entry_count);
        let expected = HEADER_BYTES as u64
            + 8 * (n + 1)
            + n
            + u64::from(header.key_blob_bytes)
            + u64::from(header.value_blob_bytes);
        if u64::try_from(bytes.len()).ok() != Some(expected) {
            let detail = format!(
                "entryCount {} and the blob sizes imply a file of {expected} bytes, but it is {} bytes",
                header.entry_count,
                bytes.len(),
            );
            return Err(Violation::new(Rule::Size, crate::layout::ENTRY_COUNT_AT, detail));
        }
        let entries = to_usize(header.entry_count, Rule::Size)?;
        let value_offsets_at = HEADER_BYTES + 4 * (entries + 1);
        let value_counts_at = value_offsets_at + 4 * (entries + 1);
        let key_blob_at = value_counts_at + entries;
        let value_blob_at = key_blob_at + to_usize(header.key_blob_bytes, Rule::Size)?;
        let file = Self {
            bytes,
            header,
            entries,
            value_offsets_at,
            value_counts_at,
            key_blob_at,
            value_blob_at,
        };

        file.check_offsets(Rule::KeyOffsets, HEADER_BYTES, header.key_blob_bytes)?;
        file.check_keys()?;
        file.check_offsets(Rule::ValueOffsets, value_offsets_at, header.value_blob_bytes)?;
        file.check_counts()?;
        file.check_tiling()?;
        Ok(file)
    }

    /// V7 and V9: `n + 1` offsets, strictly increasing, the last equal to the
    /// blob size. Strictness also makes every key and every value region
    /// non-empty.
    fn check_offsets(&self, rule: Rule, table_at: usize, blob_bytes: u32) -> Result<(), Violation> {
        let mut previous: Option<u32> = None;
        for index in 0..=self.entries {
            let at = table_at + 4 * index;
            let offset = u32_at(self.bytes, at).ok_or_else(|| Violation::new(rule, at, "offset past the file"))?;
            if let Some(before) = previous
                && offset <= before
            {
                let detail = format!("offset {index} is {offset}, not above offset {} ({before})", index - 1);
                return Err(Violation::new(rule, at, detail));
            }
            previous = Some(offset);
        }
        if previous != Some(blob_bytes) {
            let last = previous.unwrap_or_default();
            let detail = format!("the last offset is {last}, not the blob size {blob_bytes}");
            return Err(Violation::new(rule, table_at + 4 * self.entries, detail));
        }
        Ok(())
    }

    /// V8: each key is well-formed UTF-8 on its own; a sequence split across
    /// two keys is valid as a blob but leaves both keys malformed.
    fn check_keys(&self) -> Result<(), Violation> {
        for index in 0..self.entries {
            let at = self.key_blob_at + self.offset(HEADER_BYTES, index).unwrap_or_default();
            let key = self
                .key(index)
                .ok_or_else(|| Violation::new(Rule::KeyUtf8, at, "key past the file"))?;
            if !utf8::is_well_formed(key) {
                return Err(Violation::new(
                    Rule::KeyUtf8,
                    at,
                    format!("key {index} is not well-formed UTF-8"),
                ));
            }
        }
        Ok(())
    }

    /// V10: every entry has at least one candidate.
    fn check_counts(&self) -> Result<(), Violation> {
        for index in 0..self.entries {
            let at = self.value_counts_at + index;
            if self.bytes.get(at).copied().unwrap_or_default() == 0 {
                return Err(Violation::new(
                    Rule::ValueCounts,
                    at,
                    format!("entry {index} has no candidate"),
                ));
            }
        }
        Ok(())
    }

    /// V11: walking `valueCounts[i]` candidates from `valueOffsets[i]` lands
    /// exactly on `valueOffsets[i + 1]`, every length prefix inside the region.
    fn check_tiling(&self) -> Result<(), Violation> {
        for index in 0..self.entries {
            let start = self.offset(self.value_offsets_at, index).unwrap_or_default();
            let end = self.offset(self.value_offsets_at, index + 1).unwrap_or_default();
            let count = self
                .bytes
                .get(self.value_counts_at + index)
                .copied()
                .unwrap_or_default();
            let mut cursor = start;
            for candidate in 0..count {
                if cursor + CANDIDATE_HEADER_BYTES > end {
                    let detail = format!(
                        "entry {index} claims {count} candidates, but candidate {candidate} starts at {cursor} \
                         with no room for its length before {end}",
                    );
                    return Err(Violation::new(Rule::ValueTiling, self.value_blob_at + cursor, detail));
                }
                let length = u16_at(self.bytes, self.value_blob_at + cursor).unwrap_or_default();
                cursor += CANDIDATE_HEADER_BYTES + usize::from(length);
            }
            if cursor != end {
                let detail = format!("the candidates of entry {index} end at {cursor}, but its region ends at {end}");
                return Err(Violation::new(Rule::ValueTiling, self.value_blob_at + start, detail));
            }
        }
        Ok(())
    }

    /// Entry `index` of the offset table at `table_at`, relative to its blob.
    fn offset(&self, table_at: usize, index: usize) -> Option<usize> {
        usize::try_from(u32_at(self.bytes, table_at + 4 * index)?).ok()
    }

    /// The bytes of region `index`, delimited by the offset table at
    /// `table_at`, in the blob at `blob_at`.
    fn region(&self, table_at: usize, blob_at: usize, index: usize) -> Option<&'a [u8]> {
        let start = blob_at + self.offset(table_at, index)?;
        let end = blob_at + self.offset(table_at, index.checked_add(1)?)?;
        self.bytes.get(start..end)
    }

    fn key(&self, index: usize) -> Option<&'a [u8]> {
        self.region(HEADER_BYTES, self.key_blob_at, index)
    }

    /// The header as stored.
    #[must_use]
    pub const fn header(&self) -> Header {
        self.header
    }

    /// The number of entries.
    #[must_use]
    pub const fn len(&self) -> usize {
        self.entries
    }

    /// Whether the file has no entry.
    #[must_use]
    pub const fn is_empty(&self) -> bool {
        self.entries == 0
    }

    /// The whole file.
    #[must_use]
    pub const fn as_bytes(&self) -> &'a [u8] {
        self.bytes
    }

    /// The offset of key `index` in the key blob, as stored.
    #[must_use]
    pub fn key_offset(&self, index: usize) -> Option<usize> {
        (index <= self.entries)
            .then(|| self.offset(HEADER_BYTES, index))
            .flatten()
    }

    /// The offset of value region `index` in the value blob, as stored.
    #[must_use]
    pub fn value_offset(&self, index: usize) -> Option<usize> {
        (index <= self.entries)
            .then(|| self.offset(self.value_offsets_at, index))
            .flatten()
    }

    /// Entry `index`, or `None` from [`SokFile::len`] on.
    #[must_use]
    pub fn entry(&self, index: usize) -> Option<Entry<'a>> {
        if index >= self.entries {
            return None;
        }
        Some(Entry {
            key: self.key(index)?,
            values: self.region(self.value_offsets_at, self.value_blob_at, index)?,
            count: *self.bytes.get(self.value_counts_at + index)?,
        })
    }

    /// The entries in table order.
    pub fn entries(&self) -> impl Iterator<Item = Entry<'a>> + '_ {
        (0..self.entries).map_while(|index| self.entry(index))
    }
}

/// `value` as a usize, which holds every u32 on the platforms this tool runs on.
fn to_usize(value: u32, rule: Rule) -> Result<usize, Violation> {
    usize::try_from(value).map_err(|_| Violation::new(rule, 0, format!("{value} does not fit this platform's usize")))
}

/// One entry of an accepted file.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Entry<'a> {
    key: &'a [u8],
    values: &'a [u8],
    count: u8,
}

impl<'a> Entry<'a> {
    /// The key, as stored: well-formed UTF-8.
    #[must_use]
    pub const fn key(&self) -> &'a [u8] {
        self.key
    }

    /// The number of candidates, as stored.
    #[must_use]
    pub const fn candidate_count(&self) -> u8 {
        self.count
    }

    /// The candidates in order, the default first, as stored: their bytes
    /// are not checked for well-formed UTF-8 (the audit does that). The walk
    /// is bounded by the stored count here, in one place, so no consumer can
    /// run past it.
    pub fn candidates(&self) -> Take<Candidates<'a>> {
        Candidates { rest: self.values }.take(usize::from(self.count))
    }
}

/// A walk over length-prefixed candidates until their region ends, each
/// yielded without its prefix; [`Entry::candidates`] bounds it by the count.
#[derive(Debug, Clone)]
pub struct Candidates<'a> {
    rest: &'a [u8],
}

impl<'a> Iterator for Candidates<'a> {
    type Item = &'a [u8];

    fn next(&mut self) -> Option<Self::Item> {
        let length = usize::from(u16_at(self.rest, 0)?);
        let candidate = self.rest.get(CANDIDATE_HEADER_BYTES..CANDIDATE_HEADER_BYTES + length)?;
        self.rest = self.rest.get(CANDIDATE_HEADER_BYTES + length..)?;
        Some(candidate)
    }
}
