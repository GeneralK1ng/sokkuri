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

//! The text formats `sokcheck` writes.

use std::collections::BTreeMap;
use std::fmt::Write as _;
use std::io::{self, Write};

use sok::SokFile;
use sok::audit::{audit, summary};
use sok::digest::{digest, hex};
use sok::utf8::decode_first;

/// One line of an expectation file (docs/verification/sok-reader.md):
/// `<name> accept <entryCount> <maxKeyLength> <digest> <audit>` or
/// `<name> reject <rule>`, tab-separated, where `<audit>` is `audit-ok` or
/// `audit:` and the broken audit rules. Readers ignore trailing fields, so
/// later phases may append some without breaking them.
pub fn expectation(name: &str, bytes: &[u8]) -> String {
    let name = escape(name.as_bytes());
    match SokFile::parse(bytes) {
        Ok(file) => {
            let header = file.header();
            format!(
                "{name}\taccept\t{}\t{}\t{}\t{}",
                header.entry_count,
                header.max_key_length,
                hex(digest(&file)),
                summary(&audit(&file)),
            )
        }
        Err(violation) => format!("{name}\treject\t{}", violation.rule().id()),
    }
}

/// A header line, then one line per entry: the key, then each candidate,
/// tab-separated and escaped (see [`escape`]); an empty field is an empty
/// candidate.
pub fn write_dump(name: &str, file: &SokFile<'_>, out: &mut impl Write) -> io::Result<()> {
    let header = file.header();
    writeln!(
        out,
        "# {}: {} entries, maxKeyLength {}, keyBlobBytes {}, valueBlobBytes {}",
        escape(name.as_bytes()),
        header.entry_count,
        header.max_key_length,
        header.key_blob_bytes,
        header.value_blob_bytes,
    )?;
    for entry in file.entries() {
        let mut line = escape(entry.key());
        for candidate in entry.candidates() {
            line.push('\t');
            line.push_str(&escape(candidate));
        }
        writeln!(out, "{line}")?;
    }
    Ok(())
}

/// The sizes and shape of a file, for people.
pub fn write_stats(name: &str, file: &SokFile<'_>, out: &mut impl Write) -> io::Result<()> {
    let header = file.header();
    let mut groups: BTreeMap<u32, usize> = BTreeMap::new();
    let mut candidates = 0usize;
    let mut most_candidates = 0u8;
    let mut several = 0usize;
    let mut supplementary_keys = 0usize;
    for entry in file.entries() {
        if let Some((first, _)) = decode_first(entry.key()) {
            *groups.entry(u32::from(first)).or_default() += 1;
            if u32::from(first) >= 0x1_0000 {
                supplementary_keys += 1;
            }
        }
        candidates += usize::from(entry.candidate_count());
        most_candidates = most_candidates.max(entry.candidate_count());
        if entry.candidate_count() > 1 {
            several += 1;
        }
    }
    // The largest group; among equals, the lowest code point, so the output is stable.
    let largest = groups
        .iter()
        .fold(None, |best: Option<(u32, usize)>, (&code_point, &size)| match best {
            Some((_, best_size)) if best_size >= size => best,
            _ => Some((code_point, size)),
        });
    writeln!(out, "{}", escape(name.as_bytes()))?;
    writeln!(out, "  entries          {}", file.len())?;
    writeln!(
        out,
        "  bytes            {} (key blob {}, value blob {})",
        file.as_bytes().len(),
        header.key_blob_bytes,
        header.value_blob_bytes,
    )?;
    writeln!(out, "  maxKeyLength     {}", header.max_key_length)?;
    let largest = largest.map_or_else(String::new, |(code_point, size)| {
        format!(", the largest {size} keys under U+{code_point:04X}")
    });
    writeln!(out, "  key starts       {} distinct{largest}", groups.len())?;
    writeln!(
        out,
        "  supplementary    {supplementary_keys} keys start outside the BMP"
    )?;
    writeln!(
        out,
        "  candidates       {candidates}, at most {most_candidates} per entry, {several} entries with several"
    )
}

/// Bytes as text for a tab-separated line: well-formed UTF-8 as is, except
/// that `\`, tab, line feed and carriage return become `\\`, `\t`, `\n` and
/// `\r`, any other control character `\u{..}`, and every byte that does not
/// start a well-formed sequence `\x..`.
pub fn escape(mut bytes: &[u8]) -> String {
    let mut text = String::with_capacity(bytes.len());
    while let Some(&byte) = bytes.first() {
        let (consumed, scalar) = decode_first(bytes).map_or((1, None), |(scalar, length)| (length, Some(scalar)));
        match scalar {
            Some('\\') => text.push_str("\\\\"),
            Some('\t') => text.push_str("\\t"),
            Some('\n') => text.push_str("\\n"),
            Some('\r') => text.push_str("\\r"),
            Some(control) if control.is_control() => {
                let _ = write!(text, "\\u{{{:x}}}", u32::from(control));
            }
            Some(scalar) => text.push(scalar),
            None => {
                let _ = write!(text, "\\x{byte:02x}");
            }
        }
        bytes = bytes.get(consumed..).unwrap_or_default();
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn escaping_keeps_text_and_marks_everything_else() {
        assert_eq!("中a", escape("中a".as_bytes()));
        assert_eq!("a\\tb\\nc\\rd\\\\e", escape(b"a\tb\nc\rd\\e"));
        assert_eq!("\\u{0}\\u{7f}", escape(b"\0\x7f"));
        assert_eq!("\\xffa\\xe4\\xb8", escape(b"\xffa\xe4\xb8"));
        assert_eq!("", escape(b""));
    }

    #[test]
    fn a_rejected_file_expects_its_rule() {
        assert_eq!("short.sok\treject\tV1", expectation("short.sok", &[0; 4]));
    }
}
