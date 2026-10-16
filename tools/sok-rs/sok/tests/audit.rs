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

//! One case per audit rule, each an edit of a fixture that the acceptance
//! rules still let through, and the clean audit of every encoder-written file.

#![allow(clippy::indexing_slicing)]

mod common;

use std::collections::HashSet;

use common::prefixes::{KEY_BLOB, KEY_OFFSETS, VALUE_BLOB, VALUE_COUNTS, corrupt, valid};
use sok::SokFile;
use sok::audit::{AuditRule, Finding, audit, summary};

fn findings(bytes: &[u8]) -> Vec<Finding> {
    audit(&SokFile::parse(bytes).expect("an audited file is accepted first"))
}

fn rules(bytes: &[u8]) -> Vec<(AuditRule, usize)> {
    findings(bytes)
        .iter()
        .map(|finding| (finding.rule(), finding.offset()))
        .collect()
}

#[test]
fn encoder_written_files_are_audit_clean() {
    for name in ["empty.sok", "many-candidates.sok", "prefixes.sok", "widths.sok"] {
        let found = findings(&common::fixture(name));
        assert!(found.is_empty(), "{name}: {found:?}");
        assert_eq!("audit-ok", summary(&found));
    }
    for (name, bytes) in common::packaged() {
        let found = findings(&bytes);
        assert!(found.is_empty(), "{name}: {found:?}");
    }
}

#[test]
fn a1_keys_out_of_order() {
    let unsorted = corrupt(|bytes| bytes[KEY_BLOB + 3..KEY_BLOB + 6].copy_from_slice(b"aaa"));
    assert_eq!(vec![(AuditRule::KeyOrder, KEY_BLOB + 3)], rules(&unsorted));
    assert!(findings(&unsorted)[0].detail().contains("key 2 sorts before key 1"));
}

#[test]
fn a1_a_repeated_key() {
    // Key offsets [0, 1, 2, 6] re-cut the blob "aababc" as a, a and babc.
    let repeated = corrupt(|bytes| common::put_u32(bytes, KEY_OFFSETS + 8, 2));
    let found = findings(&repeated);
    assert_eq!(
        (AuditRule::KeyOrder, KEY_BLOB + 1),
        (found[0].rule(), found[0].offset())
    );
    assert!(found[0].detail().contains("key 1 repeats key 0"), "{}", found[0]);
    assert_eq!(
        "audit:A1,A2",
        summary(&found),
        "babc is also longer than maxKeyLength 3"
    );
}

#[test]
fn a2_max_key_length_understated_or_overstated() {
    for stated in [1, 2, 4, 5] {
        let edited = corrupt(|bytes| common::put_u32(bytes, 16, stated));
        assert_eq!(
            vec![(AuditRule::MaxKeyLength, 16)],
            rules(&edited),
            "maxKeyLength {stated}"
        );
    }
}

#[test]
fn a2_counts_utf16_code_units_not_bytes() {
    // widths.sok's longest key "𠀀中" is 7 bytes but 3 code units: a surrogate pair and one more.
    let mut stated_in_bytes = common::fixture("widths.sok");
    common::put_u32(&mut stated_in_bytes, 16, 7);
    let found = findings(&stated_in_bytes);
    assert_eq!(1, found.len(), "{found:?}");
    assert!(
        found[0].detail().contains("the longest key has 3 UTF-16 code units"),
        "{}",
        found[0]
    );
}

#[test]
fn a3_unreferenced_leading_bytes() {
    let mut bytes = common::fixture("empty.sok");
    common::put_u32(&mut bytes, 20, 1);
    common::put_u32(&mut bytes, 24, 2);
    common::put_u32(&mut bytes, 32, 1);
    common::put_u32(&mut bytes, 36, 2);
    bytes.extend_from_slice(&[0xFF, 0xFE, 0xFD]);
    assert_eq!(
        vec![(AuditRule::FirstOffsets, 32), (AuditRule::FirstOffsets, 36)],
        rules(&bytes)
    );
}

#[test]
fn a3_an_unreferenced_byte_before_a_full_value_blob() {
    // prefixes.sok with one byte inserted at the start of its value blob:
    // every value offset moves up by one and the blob grows by one, so the
    // acceptance rules still hold, and the finding points at the value
    // offsets, which follow the n + 1 key offsets.
    let mut bytes = valid();
    for index in 0..4 {
        let at = common::prefixes::VALUE_OFFSETS + 4 * index;
        let moved = common::get_u32(&bytes, at) + 1;
        common::put_u32(&mut bytes, at, moved);
    }
    common::put_u32(&mut bytes, 24, 13);
    bytes.insert(VALUE_BLOB, 0);
    assert_eq!(
        vec![(AuditRule::FirstOffsets, common::prefixes::VALUE_OFFSETS)],
        rules(&bytes)
    );
}

#[test]
fn findings_read_as_rule_place_and_detail() {
    let found = findings(&corrupt(|bytes| bytes[VALUE_BLOB + 2] = 0xFF));
    assert_eq!(
        "A5 at byte 73: candidate 0 of entry 0 is not well-formed UTF-8",
        found[0].to_string()
    );
    assert_eq!("candidate 0 of entry 0 is not well-formed UTF-8", found[0].detail());
}

#[test]
fn a5_empty_and_malformed_candidates() {
    let empty = corrupt(|bytes| {
        bytes[VALUE_COUNTS + 2] = 2;
        bytes[VALUE_BLOB + 7..VALUE_BLOB + 12].copy_from_slice(&[1, 0, b'C', 0, 0]);
    });
    assert_eq!(vec![(AuditRule::Candidates, VALUE_BLOB + 7)], rules(&empty));
    assert!(findings(&empty)[0].detail().contains("candidate 1 of entry 2 is empty"));

    let malformed = corrupt(|bytes| bytes[VALUE_BLOB + 2] = 0xFF);
    assert_eq!(vec![(AuditRule::Candidates, VALUE_BLOB)], rules(&malformed));
    assert!(
        findings(&malformed)[0]
            .detail()
            .contains("candidate 0 of entry 0 is not well-formed UTF-8")
    );
}

#[test]
fn findings_come_rule_by_rule_and_the_summary_names_each_rule_once() {
    let several = corrupt(|bytes| {
        bytes[KEY_BLOB + 3..KEY_BLOB + 6].copy_from_slice(b"aaa");
        bytes[VALUE_BLOB + 2] = 0xFF;
        bytes[VALUE_BLOB + 5] = 0xFF;
    });
    let found = rules(&several);
    let order: Vec<AuditRule> = found.iter().map(|(rule, _)| *rule).collect();
    assert_eq!(
        vec![AuditRule::KeyOrder, AuditRule::Candidates, AuditRule::Candidates],
        order
    );
    assert_eq!("audit:A1,A5", summary(&findings(&several)));
}

#[test]
fn every_audit_rule_has_a_case() {
    let cases = [
        corrupt(|bytes| bytes[KEY_BLOB + 3..KEY_BLOB + 6].copy_from_slice(b"aaa")),
        corrupt(|bytes| common::put_u32(bytes, 16, 1)),
        corrupt(|bytes| bytes[VALUE_BLOB + 2] = 0xFF),
        {
            let mut bytes = common::fixture("empty.sok");
            common::put_u32(&mut bytes, 20, 1);
            common::put_u32(&mut bytes, 32, 1);
            bytes.push(0);
            bytes
        },
    ];
    let covered: HashSet<AuditRule> = cases
        .iter()
        .flat_map(|bytes| rules(bytes))
        .map(|(rule, _)| rule)
        .collect();
    let missing: Vec<&str> = AuditRule::ALL
        .iter()
        .filter(|rule| !covered.contains(rule))
        .map(|rule| rule.id())
        .collect();
    assert!(missing.is_empty(), "audit rules without a case: {missing:?}");
    assert!(findings(&valid()).is_empty());
}
