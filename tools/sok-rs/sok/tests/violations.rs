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

use common::prefixes::{KEY_BLOB, KEY_OFFSETS, VALUE_BLOB, VALUE_COUNTS, VALUE_OFFSETS, corrupt, valid};

fn rule_of(bytes: &[u8]) -> Option<Rule> {
    SokFile::parse(bytes).err().map(|violation| violation.rule())
}

/// The fixture with one byte no entry refers to inserted at `blob_at`, the
/// start of a blob: the blob's size field at `size_at` and every offset of the
/// table at `offsets_at` grow by one, so only the rule that the table starts
/// at 0 is broken.
fn with_leading_byte(blob_at: usize, offsets_at: usize, size_at: usize) -> Vec<u8> {
    corrupt(|bytes| {
        for index in 0..4 {
            let at = offsets_at + 4 * index;
            let moved = common::get_u32(bytes, at) + 1;
            common::put_u32(bytes, at, moved);
        }
        let size = common::get_u32(bytes, size_at) + 1;
        common::put_u32(bytes, size_at, size);
        bytes.insert(blob_at, 0);
    })
}

/// Each case: a name, the edited file, and the rule it must break.
fn cases() -> Vec<(&'static str, Vec<u8>, Rule)> {
    vec![
        (
            "shorter than the header",
            corrupt(|bytes| bytes.truncate(31)),
            Rule::Header,
        ),
        // The Kotlin decoder never reads the reserved field, so a file cut
        // after byte 28 also fails its size check; these two show that the
        // length is checked before the first field is read.
        ("an empty file", Vec::new(), Rule::Header),
        ("only the magic", corrupt(|bytes| bytes.truncate(4)), Rule::Header),
        (
            "exactly the header and nothing else",
            corrupt(|bytes| bytes.truncate(32)),
            Rule::Size,
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
            Rule::ReservedFields,
        ),
        (
            "a non-zero reserved field",
            corrupt(|bytes| common::put_u32(bytes, 28, 7)),
            Rule::ReservedFields,
        ),
        (
            "entry count disagrees with the size",
            corrupt(|bytes| common::put_u32(bytes, 12, 4)),
            Rule::Size,
        ),
        (
            "key offsets starting above zero",
            with_leading_byte(KEY_BLOB, KEY_OFFSETS, 20),
            Rule::KeyOffsets,
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
        // A byte appended to the key blob that no key reaches: the blob and
        // the file grow by one, so this breaks only the last offset's rule.
        (
            "key offsets ending before the blob end",
            corrupt(|bytes| {
                common::put_u32(bytes, 20, 7);
                bytes.insert(VALUE_BLOB, b'x');
            }),
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
            "keys out of code-point order",
            corrupt(|bytes| bytes[KEY_BLOB + 3..KEY_BLOB + 6].copy_from_slice(b"aaa")),
            Rule::KeyOrder,
        ),
        // Key 2 as aac sorts below key 1, ab, but above key 0 and above the
        // whole blob before it, aab: only a comparison with the key just
        // before it can find it.
        (
            "a key out of order against the key before it only",
            corrupt(|bytes| bytes[KEY_BLOB + 3..KEY_BLOB + 6].copy_from_slice(b"aac")),
            Rule::KeyOrder,
        ),
        (
            "maxKeyLength below the longest key",
            corrupt(|bytes| common::put_u32(bytes, 16, 2)),
            Rule::MaxKeyLength,
        ),
        (
            "maxKeyLength above the longest key",
            corrupt(|bytes| common::put_u32(bytes, 16, 4)),
            Rule::MaxKeyLength,
        ),
        (
            "maxKeyLength beyond the key blob",
            corrupt(|bytes| common::put_u32(bytes, 16, 1000)),
            Rule::MaxKeyLength,
        ),
        // Key offsets [0, 1, 2, 6] re-cut the blob "aababc" as a, a and babc;
        // maxKeyLength 4 fits babc, so only the repetition is wrong.
        (
            "a repeated key",
            corrupt(|bytes| {
                common::put_u32(bytes, KEY_OFFSETS + 8, 2);
                common::put_u32(bytes, 16, 4);
            }),
            Rule::KeyOrder,
        ),
        (
            "value offsets starting above zero",
            with_leading_byte(VALUE_BLOB, VALUE_OFFSETS, 24),
            Rule::ValueOffsets,
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
        // The same at the end of the value blob, which ends the file: every
        // region still tiles, so only the last offset's rule is broken.
        (
            "value offsets ending before the blob end",
            corrupt(|bytes| {
                common::put_u32(bytes, 24, 13);
                bytes.push(b'x');
            }),
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
        // monotonicity check while entry 2 starts at the last byte of the file,
        // where its length prefix cannot fit; entry 1's region is stretched.
        (
            "a candidate header in the last byte",
            corrupt(|bytes| common::put_u32(bytes, VALUE_OFFSETS + 8, 11)),
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
fn the_last_byte_case_puts_entry_2_at_the_last_byte() {
    assert_eq!(valid().len() - 1, VALUE_BLOB + 11);
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

/// The byte each violation points at: the offending field, or the start of the
/// offending region, so a report leads straight to the bytes to look at.
#[test]
fn violations_point_at_the_offending_bytes() {
    let place = |edit: fn(&mut Vec<u8>)| SokFile::parse(&corrupt(edit)).expect_err("rejected");
    let last_key_offset = place(|bytes| common::put_u32(bytes, KEY_OFFSETS + 12, 1000));
    assert_eq!(
        (Rule::KeyOffsets, KEY_OFFSETS + 12),
        (last_key_offset.rule(), last_key_offset.offset())
    );
    let rising = place(|bytes| common::put_u32(bytes, KEY_OFFSETS + 4, 0));
    assert_eq!((Rule::KeyOffsets, KEY_OFFSETS + 4), (rising.rule(), rising.offset()));
    let last_value_offset = place(|bytes| common::put_u32(bytes, VALUE_OFFSETS + 12, 13));
    assert_eq!(
        (Rule::ValueOffsets, VALUE_OFFSETS + 12),
        (last_value_offset.rule(), last_value_offset.offset())
    );
    let unsorted = place(|bytes| bytes[KEY_BLOB + 3..KEY_BLOB + 6].copy_from_slice(b"aaa"));
    assert_eq!(
        "V12 at byte 70: key 2 sorts before key 1",
        unsorted.to_string(),
        "key 2 starts at byte 70"
    );
    let repeated = place(|bytes| {
        common::put_u32(bytes, KEY_OFFSETS + 8, 2);
        common::put_u32(bytes, 16, 4);
    });
    assert_eq!("V12 at byte 68: key 1 repeats key 0", repeated.to_string());
    let understated = place(|bytes| common::put_u32(bytes, 16, 2));
    assert_eq!(
        "V5 at byte 16: maxKeyLength is 2, but the longest key has 3 UTF-16 code units",
        understated.to_string()
    );
    for (bytes, rule, at) in [
        (
            with_leading_byte(KEY_BLOB, KEY_OFFSETS, 20),
            Rule::KeyOffsets,
            KEY_OFFSETS,
        ),
        (
            with_leading_byte(VALUE_BLOB, VALUE_OFFSETS, 24),
            Rule::ValueOffsets,
            VALUE_OFFSETS,
        ),
    ] {
        let first = SokFile::parse(&bytes).expect_err("rejected");
        assert_eq!((rule, at), (first.rule(), first.offset()));
        assert_eq!("the first offset is 1, not 0", first.detail());
    }

    // Entry 1's region [3, 7) cut to [3, 4): its candidate's length prefix
    // cannot fit, reported where the prefix would start.
    let too_many = place(|bytes| common::put_u32(bytes, VALUE_OFFSETS + 8, 4));
    assert_eq!(
        (Rule::ValueTiling, VALUE_BLOB + 3),
        (too_many.rule(), too_many.offset())
    );
    assert!(too_many.detail().contains("claims 1 candidates"), "{too_many}");
    // Entry 0's candidate lengthened by one byte: reported at the region start.
    let overshoot = place(|bytes| common::put_u16(bytes, VALUE_BLOB, 2));
    assert_eq!((Rule::ValueTiling, VALUE_BLOB), (overshoot.rule(), overshoot.offset()));
    assert!(
        overshoot.detail().contains("end at 4, but its region ends at 3"),
        "{overshoot}"
    );
    // Entry 1's candidate lengthened, so the region start is not zero.
    let later = place(|bytes| common::put_u16(bytes, VALUE_BLOB + 3, 3));
    assert_eq!((Rule::ValueTiling, VALUE_BLOB + 3), (later.rule(), later.offset()));
    let empty_entry = place(|bytes| bytes[VALUE_COUNTS + 1] = 0);
    assert_eq!(
        (Rule::ValueCounts, VALUE_COUNTS + 1),
        (empty_entry.rule(), empty_entry.offset())
    );
}

#[test]
fn violations_name_their_rule_and_place() {
    let violation = SokFile::parse(&corrupt(|bytes| bytes[KEY_BLOB + 1] = 0xED)).expect_err("rejected");
    assert_eq!(KEY_BLOB + 1, violation.offset());
    assert!(violation.detail().contains("key 1"), "{violation}");
    assert!(violation.to_string().starts_with("V8 at byte 68: "), "{violation}");
}

#[test]
fn max_key_length_counts_utf16_code_units_not_bytes() {
    // widths.sok's longest key "𠀀中" is 7 bytes but 3 code units: a surrogate pair and one more.
    let mut stated_in_bytes = common::fixture("widths.sok");
    common::put_u32(&mut stated_in_bytes, 16, 7);
    let violation = SokFile::parse(&stated_in_bytes).expect_err("rejected");
    assert_eq!(Rule::MaxKeyLength, violation.rule());
    assert!(
        violation.detail().ends_with("the longest key has 3 UTF-16 code units"),
        "{violation}"
    );
    assert_eq!(
        3,
        SokFile::parse(&common::fixture("widths.sok"))
            .expect("valid")
            .header()
            .max_key_length
    );
}

/// Edits both readers accept, however odd: each breaks an audit rule, never
/// an acceptance rule. The audit (A1 to A5) is where such files are
/// questioned, not the acceptance.
fn accepted_oddities() -> Vec<(&'static str, Vec<u8>)> {
    vec![
        // Entry 2's five value bytes re-cut as an empty candidate and "C", and
        // the other way round, where the empty candidate's prefix ends the region.
        (
            "an empty first candidate",
            corrupt(|bytes| {
                bytes[VALUE_COUNTS + 2] = 2;
                bytes[VALUE_BLOB + 7..VALUE_BLOB + 12].copy_from_slice(&[0, 0, 1, 0, b'C']);
            }),
        ),
        (
            "an empty last candidate",
            corrupt(|bytes| {
                bytes[VALUE_COUNTS + 2] = 2;
                bytes[VALUE_BLOB + 7..VALUE_BLOB + 12].copy_from_slice(&[1, 0, b'C', 0, 0]);
            }),
        ),
        ("a malformed candidate", corrupt(|bytes| bytes[VALUE_BLOB + 2] = 0xFF)),
    ]
}

fn oddity(name: &str) -> Vec<u8> {
    accepted_oddities()
        .into_iter()
        .find(|(case, _)| *case == name)
        .map(|(_, bytes)| bytes)
        .expect("a named oddity")
}

#[test]
fn oddities_the_runtime_accepts_are_accepted() {
    for (name, bytes) in accepted_oddities() {
        let file = SokFile::parse(&bytes).unwrap_or_else(|violation| panic!("{name}: {violation}"));
        assert!(!sok::audit::audit(&file).is_empty(), "{name} breaks no audit rule");
    }
}

#[test]
fn an_empty_candidate_reads_as_empty_wherever_it_stands() {
    for (name, expected) in [
        ("an empty first candidate", [&b""[..], b"C"]),
        ("an empty last candidate", [b"C", b""]),
    ] {
        let bytes = oddity(name);
        let file = SokFile::parse(&bytes).expect("accepted");
        let candidates: Vec<&[u8]> = file.entry(2).expect("entry 2").candidates().collect();
        assert_eq!(expected.to_vec(), candidates, "{name}");
    }
}

/// Writes every case of this file into the directory `SOKKURI_SOK_SEEDS`
/// names, for the conformance corpus (docs/verification/sok-reader.md): each breaks
/// exactly one acceptance rule, or none, in an otherwise valid file. Fuzzed
/// inputs mostly break several rules at once, so they cannot show that the
/// Kotlin decoder still has each check; these can. Run by
/// `./gradlew :tools:sok-rs:importCorpusSeeds`.
#[test]
#[ignore = "a generator, run with --ignored by importCorpusSeeds"]
fn write_corpus_seeds() {
    let directory = std::path::PathBuf::from(std::env::var_os("SOKKURI_SOK_SEEDS").expect("SOKKURI_SOK_SEEDS is set"));
    std::fs::create_dir_all(&directory).expect("seed directory");
    let named = cases()
        .into_iter()
        .map(|(name, bytes, _)| (name, bytes))
        .chain(accepted_oddities());
    for (name, bytes) in named {
        std::fs::write(directory.join(name.replace(' ', "-")), bytes).expect("seed file");
    }
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
