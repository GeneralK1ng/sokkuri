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

//! The byte layout of `.sok` version 1, as the KDoc of `SokFormatLayout`
//! specifies it. Every multi-byte field is unsigned and little-endian:
//!
//! ```text
//! Offset  Size      Field
//! 0       4         magic "SOK1"
//! 4       4         formatVersion = 1
//! 8       4         flags = 0
//! 12      4         entryCount
//! 16      4         maxKeyLength (UTF-16 code units)
//! 20      4         keyBlobBytes
//! 24      4         valueBlobBytes
//! 28      4         reserved = 0
//! 32      4×(n+1)   keyOffsets
//! …       4×(n+1)   valueOffsets
//! …       n         valueCounts (u8)
//! …       *         keyBlob
//! …       *         valueBlob: per candidate a u16 byte length, then the bytes
//! ```

/// The four bytes every `.sok` file starts with.
pub const MAGIC: [u8; 4] = *b"SOK1";

/// The only format version this reader knows.
pub const VERSION: u32 = 1;

/// Size of the fixed header; the key offsets start right after it.
pub const HEADER_BYTES: usize = 32;

/// Size of the byte-length prefix of every candidate in the value blob.
pub const CANDIDATE_HEADER_BYTES: usize = 2;

pub(crate) const VERSION_AT: usize = 4;
pub(crate) const FLAGS_AT: usize = 8;
pub(crate) const ENTRY_COUNT_AT: usize = 12;
pub(crate) const MAX_KEY_LENGTH_AT: usize = 16;
pub(crate) const KEY_BLOB_BYTES_AT: usize = 20;
pub(crate) const VALUE_BLOB_BYTES_AT: usize = 24;
pub(crate) const RESERVED_AT: usize = 28;

/// The little-endian u16 at `at`, or `None` past the end of `bytes`.
pub(crate) fn u16_at(bytes: &[u8], at: usize) -> Option<u16> {
    let field = bytes.get(at..at.checked_add(2)?)?;
    Some(u16::from_le_bytes(field.try_into().ok()?))
}

/// The little-endian u32 at `at`, or `None` past the end of `bytes`.
pub(crate) fn u32_at(bytes: &[u8], at: usize) -> Option<u32> {
    let field = bytes.get(at..at.checked_add(4)?)?;
    Some(u32::from_le_bytes(field.try_into().ok()?))
}

/// The fixed header, field by field, as stored.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Header {
    /// `formatVersion`.
    pub version: u32,
    /// `flags`; version 1 requires zero.
    pub flags: u32,
    /// `entryCount`: the number of entries, `n`.
    pub entry_count: u32,
    /// `maxKeyLength`: the longest key, in UTF-16 code units.
    pub max_key_length: u32,
    /// `keyBlobBytes`: the size of the key blob.
    pub key_blob_bytes: u32,
    /// `valueBlobBytes`: the size of the value blob.
    pub value_blob_bytes: u32,
    /// The reserved field at offset 28; the specification says zero.
    pub reserved: u32,
}

impl Header {
    /// Reads the header from the start of `bytes`, or `None` when `bytes`
    /// is shorter than [`HEADER_BYTES`].
    pub(crate) fn read(bytes: &[u8]) -> Option<Self> {
        Some(Self {
            version: u32_at(bytes, VERSION_AT)?,
            flags: u32_at(bytes, FLAGS_AT)?,
            entry_count: u32_at(bytes, ENTRY_COUNT_AT)?,
            max_key_length: u32_at(bytes, MAX_KEY_LENGTH_AT)?,
            key_blob_bytes: u32_at(bytes, KEY_BLOB_BYTES_AT)?,
            value_blob_bytes: u32_at(bytes, VALUE_BLOB_BYTES_AT)?,
            reserved: u32_at(bytes, RESERVED_AT)?,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fields_are_little_endian_and_bounded() {
        let bytes = [0x01, 0x02, 0x03, 0x04, 0x05];
        assert_eq!(Some(0x0201), u16_at(&bytes, 0));
        assert_eq!(Some(0x0504_0302), u32_at(&bytes, 1));
        assert_eq!(None, u32_at(&bytes, 2));
        assert_eq!(None, u16_at(&bytes, 4));
        assert_eq!(None, u16_at(&bytes, usize::MAX));
        assert_eq!(None, u32_at(&bytes, usize::MAX - 1));
    }

    #[test]
    fn header_needs_all_thirty_two_bytes() {
        assert_eq!(None, Header::read(&[0; HEADER_BYTES - 1]));
        let mut bytes = [0u8; HEADER_BYTES];
        bytes[..4].copy_from_slice(&MAGIC);
        bytes[RESERVED_AT] = 7;
        assert_eq!(Some(7), Header::read(&bytes).map(|header| header.reserved));
    }
}
