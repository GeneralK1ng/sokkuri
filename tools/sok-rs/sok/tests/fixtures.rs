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

//! The reader sees exactly what the Kotlin encoder wrote: the fixtures'
//! contents are spelled out here as `SokFixtureFilesTest` defines them, with
//! the entries in the code-point order the encoder sorts them into.

#![allow(clippy::indexing_slicing)]

mod common;

use sok::SokFile;

fn contents(bytes: &[u8]) -> (u32, Vec<(String, Vec<String>)>) {
    let file = SokFile::parse(bytes).expect("an encoder-written fixture is accepted");
    let entries = file
        .entries()
        .map(|entry| {
            let key = String::from_utf8(entry.key().to_vec()).expect("keys are well-formed");
            let candidates = entry
                .candidates()
                .map(|candidate| String::from_utf8(candidate.to_vec()).expect("fixture values are well-formed"))
                .collect();
            (key, candidates)
        })
        .collect();
    (file.header().max_key_length, entries)
}

fn owned(entries: &[(&str, &[&str])]) -> Vec<(String, Vec<String>)> {
    entries
        .iter()
        .map(|(key, candidates)| {
            (
                (*key).to_owned(),
                candidates.iter().map(|candidate| (*candidate).to_owned()).collect(),
            )
        })
        .collect()
}

#[test]
fn prefixes() {
    let expected = owned(&[("a", &["A"]), ("ab", &["AB"]), ("abc", &["ABC"])]);
    assert_eq!((3, expected), contents(&common::fixture("prefixes.sok")));
}

#[test]
fn widths_in_code_point_order() {
    let expected = owned(&[
        ("z", &["Z"]),
        ("é", &["É", "e"]),
        ("中", &["中", "衆"]),
        ("中文", &["中文"]),
        ("\u{FFFD}", &["?"]),
        ("𠀀", &["x", "y", "z"]),
        ("𠀀中", &["\u{FFFD}"]),
    ]);
    // "𠀀中" is three UTF-16 units: a surrogate pair and one BMP unit.
    assert_eq!((3, expected), contents(&common::fixture("widths.sok")));
}

#[test]
fn empty() {
    let bytes = common::fixture("empty.sok");
    let file = SokFile::parse(&bytes).expect("accepted");
    assert!(file.is_empty());
    assert_eq!(0, file.header().max_key_length);
    assert_eq!(None, file.entry(0));
    assert_eq!(0, file.entries().count());
}

#[test]
fn many_candidates_reach_the_limit() {
    let (_, entries) = contents(&common::fixture("many-candidates.sok"));
    assert_eq!(1, entries.len());
    let (key, candidates) = &entries[0];
    assert_eq!("k", key);
    assert_eq!(255, candidates.len());
    for (index, candidate) in candidates.iter().enumerate() {
        let letter = char::from(b'a' + u8::try_from(index % 26).expect("below 26"));
        assert_eq!(letter.to_string(), *candidate, "candidate {index}");
    }
}

#[test]
fn offsets_are_what_the_layout_implies() {
    // a/ab/abc: keys of 1, 2 and 3 bytes; candidates of 1, 2 and 3 bytes,
    // each after a two-byte length prefix.
    let bytes = common::fixture("prefixes.sok");
    let file = SokFile::parse(&bytes).expect("accepted");
    let keys: Vec<_> = (0..=3).map(|index| file.key_offset(index)).collect();
    let values: Vec<_> = (0..=3).map(|index| file.value_offset(index)).collect();
    assert_eq!(vec![Some(0), Some(1), Some(3), Some(6)], keys);
    assert_eq!(vec![Some(0), Some(3), Some(7), Some(12)], values);
    assert_eq!(None, file.key_offset(4));
}
