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

use common::prefixes::{VALUE_BLOB, VALUE_COUNTS, corrupt, valid};
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
fn findings_come_in_table_order_and_the_summary_names_each_rule_once() {
    // Malformed candidates in entries 1 and 0, whose regions start 3 bytes apart.
    let several = corrupt(|bytes| {
        bytes[VALUE_BLOB + 5] = 0xFF;
        bytes[VALUE_BLOB + 2] = 0xFF;
    });
    assert_eq!(
        vec![
            (AuditRule::Candidates, VALUE_BLOB),
            (AuditRule::Candidates, VALUE_BLOB + 3)
        ],
        rules(&several)
    );
    assert_eq!("audit:A5", summary(&findings(&several)));
}

#[test]
fn every_audit_rule_has_a_case() {
    let cases = [corrupt(|bytes| bytes[VALUE_BLOB + 2] = 0xFF)];
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
