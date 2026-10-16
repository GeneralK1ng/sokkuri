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

//! One negative case per acceptance rule, each a single edit of the
//! `prefixes.sok` fixture mirroring a corruption class of the Kotlin
//! `SokDecoderValidationTest`, and the edits both readers accept: the rules
//! must reject exactly what the runtime decoder rejects, no more.

#![allow(clippy::indexing_slicing)]

mod common;

use std::collections::HashSet;

use sok::{Rule, SokFile};

/// `prefixes.sok` holds a, ab and abc with candidates A, AB and ABC: n = 3,
/// key offsets [0, 1, 3, 6] at 32, value offsets [0, 3, 7, 12] at 48, value
/// counts at 64, the key blob at 67 and the value blob at 73, 85 bytes in all.
const KEY_OFFSETS: usize = 32;
const VALUE_OFFSETS: usize = 48;
const VALUE_COUNTS: usize = 64;
const KEY_BLOB: usize = 67;
const VALUE_BLOB: usize = 73;

fn valid() -> Vec<u8> {
    let bytes = common::fixture("prefixes.sok");
    assert_eq!(85, bytes.len(), "the layout constants above describe prefixes.sok");
    bytes
}

fn corrupt(edit: impl FnOnce(&mut Vec<u8>)) -> Vec<u8> {
    let mut bytes = valid();
    edit(&mut bytes);
    bytes
}

fn rule_of(bytes: &[u8]) -> Option<Rule> {
    SokFile::parse(bytes).err().map(|violation| violation.rule())
}

/// Each case: a name, the edited file, and the rule it must break.
fn cases() -> Vec<(&'static str, Vec<u8>, Rule)> {
    vec![
        (
            "shorter than the header",
            corrupt(|bytes| bytes.truncate(31)),
            Rule::Header,
        ),
        ("truncated by one byte", corrupt(|bytes| bytes.truncate(84)), Rule::Size),
        ("bad magic", corrupt(|bytes| bytes[0] = b'X'), Rule::Magic),
        (
            "unsupported version",
            corrupt(|bytes| common::put_u32(bytes, 4, 2)),
            Rule::Version,
        ),
        (
            "non-zero flags",
            corrupt(|bytes| common::put_u32(bytes, 8, 1)),
            Rule::Flags,
        ),
        (
            "entry count disagrees with the size",
            corrupt(|bytes| common::put_u32(bytes, 12, 4)),
            Rule::Size,
        ),
        (
            "maxKeyLength beyond the key blob",
            corrupt(|bytes| common::put_u32(bytes, 16, 1000)),
            Rule::MaxKeyLength,
        ),
        (
            "key offsets not increasing",
            corrupt(|bytes| common::put_u32(bytes, KEY_OFFSETS + 4, 0)),
            Rule::KeyOffsets,
        ),
        (
            "key offsets ending past the blob",
            corrupt(|bytes| common::put_u32(bytes, KEY_OFFSETS + 12, 1000)),
            Rule::KeyOffsets,
        ),
        (
            "malformed key 0",
            corrupt(|bytes| bytes[KEY_BLOB] = 0x80),
            Rule::KeyUtf8,
        ),
        (
            "malformed key 1",
            corrupt(|bytes| bytes[KEY_BLOB + 1] = 0xED),
            Rule::KeyUtf8,
        ),
        (
            "a sequence split across keys 0 and 1",
            corrupt(|bytes| {
                bytes[KEY_BLOB] = 0xC3;
                bytes[KEY_BLOB + 1] = 0xA9;
            }),
            Rule::KeyUtf8,
        ),
        (
            "value offsets not increasing",
            corrupt(|bytes| common::put_u32(bytes, VALUE_OFFSETS + 4, 0)),
            Rule::ValueOffsets,
        ),
        (
            "value offsets ending past the blob",
            corrupt(|bytes| common::put_u32(bytes, VALUE_OFFSETS + 12, 13)),
            Rule::ValueOffsets,
        ),
        (
            "an entry with no candidate",
            corrupt(|bytes| bytes[VALUE_COUNTS] = 0),
            Rule::ValueCounts,
        ),
        (
            "more candidates than the region holds",
            corrupt(|bytes| common::put_u32(bytes, VALUE_OFFSETS + 4, 1)),
            Rule::ValueTiling,
        ),
        (
            "a candidate overshooting its region",
            corrupt(|bytes| common::put_u16(bytes, VALUE_BLOB, 100)),
            Rule::ValueTiling,
        ),
        (
            "a candidate undershooting its region",
            corrupt(|bytes| common::put_u16(bytes, VALUE_BLOB, 0)),
            Rule::ValueTiling,
        ),
        // The regression the Kotlin decoder once had: offsets that pass every
        // monotonicity check while entry 1's region no longer fits its candidate.
        (
            "a region one byte too long",
            corrupt(|bytes| common::put_u32(bytes, VALUE_OFFSETS + 8, 8)),
            Rule::ValueTiling,
        ),
    ]
}

#[test]
fn every_corruption_breaks_its_rule() {
    for (name, bytes, rule) in cases() {
        assert_eq!(Some(rule), rule_of(&bytes), "{name}");
    }
}

#[test]
fn every_rule_has_a_case() {
    let covered: HashSet<Rule> = cases().into_iter().map(|(_, _, rule)| rule).collect();
    let missing: Vec<&str> = Rule::ALL
        .iter()
        .filter(|rule| !covered.contains(rule))
        .map(|rule| rule.id())
        .collect();
    assert!(missing.is_empty(), "rules without a negative case: {missing:?}");
}

#[test]
fn violations_name_their_rule_and_place() {
    let violation = SokFile::parse(&corrupt(|bytes| bytes[KEY_BLOB + 1] = 0xED)).expect_err("rejected");
    assert_eq!(KEY_BLOB + 1, violation.offset());
    assert!(violation.detail().contains("key 1"), "{violation}");
    assert!(violation.to_string().starts_with("V8 at byte 68: "), "{violation}");
}

/// What the runtime decoder accepts, this reader accepts too, however odd:
/// the audit (P2) is where such files are questioned, not the acceptance.
#[test]
fn oddities_the_runtime_accepts_are_accepted() {
    let reserved = corrupt(|bytes| common::put_u32(bytes, 28, 7));
    assert_eq!(None, rule_of(&reserved), "a non-zero reserved field");

    let unsorted = corrupt(|bytes| bytes[KEY_BLOB + 3..KEY_BLOB + 6].copy_from_slice(b"aaa"));
    assert_eq!(None, rule_of(&unsorted), "keys out of code-point order");

    let understated = corrupt(|bytes| common::put_u32(bytes, 16, 1));
    assert_eq!(None, rule_of(&understated), "maxKeyLength below the longest key");

    // Entry 2's five value bytes re-cut as an empty candidate and "C".
    let empty_candidate = corrupt(|bytes| {
        bytes[VALUE_COUNTS + 2] = 2;
        bytes[VALUE_BLOB + 7..VALUE_BLOB + 12].copy_from_slice(&[0, 0, 1, 0, b'C']);
    });
    let file = SokFile::parse(&empty_candidate).expect("an empty candidate is accepted");
    let candidates: Vec<&[u8]> = file.entry(2).expect("entry 2").candidates().collect();
    assert_eq!(vec![&b""[..], &b"C"[..]], candidates);
}

#[test]
fn unreferenced_leading_blob_bytes_are_accepted() {
    // The empty dictionary with one byte before its (empty) key region and
    // two before its value region: offsets need only increase strictly and
    // end at the blob size, so a first offset above zero passes.
    let mut bytes = common::fixture("empty.sok");
    assert_eq!(40, bytes.len());
    common::put_u32(&mut bytes, 20, 1);
    common::put_u32(&mut bytes, 24, 2);
    common::put_u32(&mut bytes, 32, 1);
    common::put_u32(&mut bytes, 36, 2);
    bytes.extend_from_slice(&[0xFF, 0xFE, 0xFD]);
    let file = SokFile::parse(&bytes).expect("accepted, as the runtime decoder accepts it");
    assert_eq!(Some(1), file.key_offset(0));
    assert_eq!(Some(2), file.value_offset(0));
}

#[test]
fn a_huge_entry_count_is_a_size_violation_not_an_overflow() {
    let bytes = corrupt(|bytes| common::put_u32(bytes, 12, u32::MAX));
    assert_eq!(Some(Rule::Size), rule_of(&bytes));
    let blobs = corrupt(|bytes| {
        common::put_u32(bytes, 20, u32::MAX);
        common::put_u32(bytes, 16, 0);
        common::put_u32(bytes, 24, u32::MAX);
    });
    assert_eq!(Some(Rule::Size), rule_of(&blobs));
}
