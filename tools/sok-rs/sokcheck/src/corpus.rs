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

//! The conformance corpus (docs/design/sok-rs.md §6.2): the inputs the fuzzer
//! found, committed as text so a review can read the diff, and replayed by the
//! Kotlin decoder on every platform against the expectations written here.
//!
//! One input per line, `<id>` TAB `<hex bytes>`, sorted by id, where the id
//! is the input's FNV-1a 64 in 16 lowercase hex digits. Lines starting with
//! `#` are comments. The file is only ever written by `corpus import`; the id
//! makes a hand-edited line fail to load instead of silently changing meaning.

use std::collections::BTreeMap;
use std::fmt::Write as _;
use std::fs;
use std::path::Path;

use sok::digest::{Fnv1a64, hex};

use crate::Failure;

/// The largest input the corpus keeps, the fuzzer's `-max_len`.
pub const MAX_INPUT_BYTES: usize = 512;

const HEADER: &str = "\
# The .sok conformance corpus (docs/design/sok-rs.md §6.2): one input per line,
# its FNV-1a 64 id, a tab, its bytes in hex. Written by `sokcheck corpus import`;
# never edit it by hand. expected.txt beside it is `sokcheck expect --corpus`.
";

/// The corpus as id → bytes, ordered by id.
pub type Corpus = BTreeMap<String, Vec<u8>>;

/// The id of an input: its FNV-1a 64 as 16 lowercase hex digits.
pub fn id_of(bytes: &[u8]) -> String {
    let mut hash = Fnv1a64::new();
    hash.write(bytes);
    hex(hash.finish())
}

/// Reads a corpus file; a missing file is an empty corpus.
pub fn read(path: &Path) -> Result<Corpus, Failure> {
    if !path.exists() {
        return Ok(Corpus::new());
    }
    let text = fs::read_to_string(path).map_err(|error| Failure::Error(format!("{}: {error}", path.display())))?;
    parse(&text).map_err(|message| Failure::Error(format!("{}: {message}", path.display())))
}

/// Parses corpus text, checking every id against its bytes.
pub fn parse(text: &str) -> Result<Corpus, String> {
    let mut corpus = Corpus::new();
    for (number, line) in text.lines().enumerate().map(|(index, line)| (index + 1, line)) {
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let (id, encoded) = line.split_once('\t').ok_or_else(|| format!("line {number}: no tab"))?;
        let bytes = decode_hex(encoded).ok_or_else(|| format!("line {number}: not hexadecimal bytes"))?;
        if id_of(&bytes) != id {
            return Err(format!("line {number}: id {id} does not match its bytes"));
        }
        if corpus.insert(id.to_owned(), bytes).is_some() {
            return Err(format!("line {number}: id {id} repeats"));
        }
    }
    Ok(corpus)
}

/// The corpus as text: the header, then one line per input in id order.
pub fn format(corpus: &Corpus) -> String {
    let mut text = HEADER.to_owned();
    for (id, bytes) in corpus {
        let _ = writeln!(text, "{id}\t{}", encode_hex(bytes));
    }
    text
}

/// What `corpus import` did.
pub struct Import {
    pub added: usize,
    pub known: usize,
    pub too_large: usize,
}

/// Adds every file directly inside `directory` to `corpus`, skipping files
/// above [`MAX_INPUT_BYTES`] and inputs it already holds.
pub fn import(corpus: &mut Corpus, directory: &Path) -> Result<Import, Failure> {
    let listing =
        fs::read_dir(directory).map_err(|error| Failure::Error(format!("{}: {error}", directory.display())))?;
    let mut import = Import {
        added: 0,
        known: 0,
        too_large: 0,
    };
    for entry in listing {
        let path = entry
            .map_err(|error| Failure::Error(format!("{}: {error}", directory.display())))?
            .path();
        if !path.is_file() {
            continue;
        }
        let bytes = fs::read(&path).map_err(|error| Failure::Error(format!("{}: {error}", path.display())))?;
        if bytes.len() > MAX_INPUT_BYTES {
            import.too_large += 1;
        } else if corpus.insert(id_of(&bytes), bytes).is_some() {
            import.known += 1;
        } else {
            import.added += 1;
        }
    }
    Ok(import)
}

/// Writes every input of `corpus` into `directory` as a file named by its id,
/// the form libFuzzer reads a corpus in.
pub fn export(corpus: &Corpus, directory: &Path) -> Result<(), Failure> {
    fs::create_dir_all(directory).map_err(|error| Failure::Error(format!("{}: {error}", directory.display())))?;
    for (id, bytes) in corpus {
        let path = directory.join(id);
        fs::write(&path, bytes).map_err(|error| Failure::Error(format!("{}: {error}", path.display())))?;
    }
    Ok(())
}

fn encode_hex(bytes: &[u8]) -> String {
    let mut text = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        let _ = write!(text, "{byte:02x}");
    }
    text
}

fn decode_hex(text: &str) -> Option<Vec<u8>> {
    if !text.len().is_multiple_of(2) {
        return None;
    }
    let (pairs, _) = text.as_bytes().as_chunks::<2>();
    pairs
        .iter()
        .map(|pair| u8::from_str_radix(std::str::from_utf8(pair).ok()?, 16).ok())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_corpus_survives_formatting_and_parsing() {
        let mut corpus = Corpus::new();
        for input in [&b""[..], b"SOK1", &[0x00, 0xFF, 0x7F]] {
            corpus.insert(id_of(input), input.to_vec());
        }
        let text = format(&corpus);
        assert!(text.starts_with("# The .sok conformance corpus"));
        assert_eq!(Ok(corpus), parse(&text));
    }

    #[test]
    fn lines_are_ordered_by_id() {
        let mut corpus = Corpus::new();
        for input in [&b"b"[..], b"a", b"c"] {
            corpus.insert(id_of(input), input.to_vec());
        }
        let ids: Vec<String> = format(&corpus)
            .lines()
            .filter(|line| !line.starts_with('#'))
            .map(|line| line[..16].to_owned())
            .collect();
        let mut sorted = ids.clone();
        sorted.sort();
        assert_eq!(sorted, ids);
    }

    #[test]
    fn a_line_that_does_not_match_its_id_is_refused() {
        let good = format!("{}\t534f4b31\n", id_of(b"SOK1"));
        assert!(parse(&good).is_ok());
        assert!(
            parse(&good.replace("534f4b31", "534f4b32"))
                .unwrap_err()
                .contains("does not match")
        );
        assert!(parse("0000000000000000\t5\n").unwrap_err().contains("not hexadecimal"));
        assert!(parse("0000000000000000\tzz\n").unwrap_err().contains("not hexadecimal"));
        assert!(parse("no tab here\n").unwrap_err().contains("no tab"));
        assert!(parse(&format!("{good}{good}")).unwrap_err().contains("repeats"));
    }

    #[test]
    fn hex_round_trips_every_byte() {
        let every: Vec<u8> = (0..=u8::MAX).collect();
        assert_eq!(Some(every.clone()), decode_hex(&encode_hex(&every)));
        assert_eq!("00ff10", encode_hex(&[0x00, 0xFF, 0x10]));
    }
}
