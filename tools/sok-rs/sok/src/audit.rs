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

//! The audit rules of docs/design/sok-rs.md §4.2: what the specification
//! states and the encoder guarantees, but the runtime decoder does not check.
//! An accepted file can break them; the audit says which, and never changes
//! whether the file is accepted. A rule the decoder came to enforce moved to
//! the acceptance rules, and its identifier is not reused: A4, the reserved
//! field, is part of V4.

use std::fmt;

use crate::layout::HEADER_BYTES;
use crate::reader::SokFile;
use crate::utf8;

/// An audit rule (docs/design/sok-rs.md §4.2).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
#[non_exhaustive]
pub enum AuditRule {
    /// A1: keys increase strictly in code-point order (invariant I2), so
    /// they are sorted and unique; retrieval's binary searches rely on it.
    KeyOrder,
    /// A2: `maxKeyLength` equals the longest key in UTF-16 code units
    /// (invariant I1); retrieval cuts its window to it.
    MaxKeyLength,
    /// A3: the first key offset and the first value offset are zero, so
    /// neither blob starts with bytes no entry refers to.
    FirstOffsets,
    /// A5: every candidate is non-empty and well-formed UTF-8.
    Candidates,
}

impl AuditRule {
    /// Every rule, in the order the audit checks them.
    pub const ALL: [Self; 4] = [Self::KeyOrder, Self::MaxKeyLength, Self::FirstOffsets, Self::Candidates];

    /// The rule's stable identifier, never reused for another rule.
    #[must_use]
    pub const fn id(self) -> &'static str {
        match self {
            Self::KeyOrder => "A1",
            Self::MaxKeyLength => "A2",
            Self::FirstOffsets => "A3",
            Self::Candidates => "A5",
        }
    }
}

/// One place where an accepted file breaks an audit rule.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Finding {
    rule: AuditRule,
    offset: usize,
    detail: String,
}

impl Finding {
    fn new(rule: AuditRule, offset: usize, detail: impl Into<String>) -> Self {
        Self {
            rule,
            offset,
            detail: detail.into(),
        }
    }

    /// The rule the file breaks here.
    #[must_use]
    pub const fn rule(&self) -> AuditRule {
        self.rule
    }

    /// The byte offset of the field or region concerned.
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

impl fmt::Display for Finding {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{} at byte {}: {}", self.rule.id(), self.offset, self.detail)
    }
}

/// Every finding in `file`, rule by rule in [`AuditRule::ALL`] order, entries
/// in table order within a rule. Empty when the file is audit-clean.
#[must_use]
pub fn audit(file: &SokFile<'_>) -> Vec<Finding> {
    let mut findings = Vec::new();
    check_key_order(file, &mut findings);
    check_max_key_length(file, &mut findings);
    check_first_offsets(file, &mut findings);
    check_candidates(file, &mut findings);
    findings
}

/// The audit verdict as one field of an expectation line: `audit-ok`, or
/// `audit:` and the ids of the broken rules, each once, in rule order.
#[must_use]
pub fn summary(findings: &[Finding]) -> String {
    let rules: Vec<&str> = AuditRule::ALL
        .iter()
        .filter(|rule| findings.iter().any(|finding| finding.rule == **rule))
        .map(|rule| rule.id())
        .collect();
    if rules.is_empty() {
        "audit-ok".to_owned()
    } else {
        format!("audit:{}", rules.join(","))
    }
}

/// Where key `index` starts in the file, for findings about it.
fn key_at(file: &SokFile<'_>, index: usize) -> usize {
    file.key_blob_offset() + file.key_offset(index).unwrap_or_default()
}

/// Where entry `index`'s candidates start in the file.
fn values_at(file: &SokFile<'_>, index: usize) -> usize {
    file.value_blob_offset() + file.value_offset(index).unwrap_or_default()
}

/// A1. Keys are well-formed UTF-8 (V8), and for well-formed UTF-8 the byte
/// order is the code-point order, so comparing bytes is comparing code points.
fn check_key_order(file: &SokFile<'_>, findings: &mut Vec<Finding>) {
    let mut previous: Option<&[u8]> = None;
    for (index, entry) in file.entries().enumerate() {
        if let Some(before) = previous
            && before >= entry.key()
        {
            let how = if before == entry.key() {
                "repeats"
            } else {
                "sorts before"
            };
            let detail = format!("key {index} {how} key {}", index - 1);
            findings.push(Finding::new(AuditRule::KeyOrder, key_at(file, index), detail));
        }
        previous = Some(entry.key());
    }
}

/// A2. The longest key in UTF-16 code units, which is the unit of the field.
fn check_max_key_length(file: &SokFile<'_>, findings: &mut Vec<Finding>) {
    let longest = file
        .entries()
        .filter_map(|entry| utf8::utf16_length(entry.key()))
        .max()
        .unwrap_or(0);
    let stored = file.header().max_key_length;
    if u32::try_from(longest).ok() != Some(stored) {
        let detail = format!("maxKeyLength is {stored}, but the longest key has {longest} UTF-16 code units");
        findings.push(Finding::new(
            AuditRule::MaxKeyLength,
            crate::layout::MAX_KEY_LENGTH_AT,
            detail,
        ));
    }
}

/// A3. Offsets need only increase strictly (V7, V9), so a first offset above
/// zero passes the acceptance rules while leaving bytes nobody refers to.
fn check_first_offsets(file: &SokFile<'_>, findings: &mut Vec<Finding>) {
    let first_key = file.key_offset(0).unwrap_or_default();
    if first_key != 0 {
        let detail = format!("the key blob starts with {first_key} bytes no key refers to");
        findings.push(Finding::new(AuditRule::FirstOffsets, HEADER_BYTES, detail));
    }
    let first_value = file.value_offset(0).unwrap_or_default();
    if first_value != 0 {
        let detail = format!("the value blob starts with {first_value} bytes no candidate refers to");
        let value_offsets_at = HEADER_BYTES + 4 * (file.len() + 1);
        findings.push(Finding::new(AuditRule::FirstOffsets, value_offsets_at, detail));
    }
}

/// A5. Values decode leniently at run time, so neither an empty nor a
/// malformed candidate is rejected; the encoder writes neither.
fn check_candidates(file: &SokFile<'_>, findings: &mut Vec<Finding>) {
    for (index, entry) in file.entries().enumerate() {
        for (position, candidate) in entry.candidates().enumerate() {
            let problem = if candidate.is_empty() {
                "is empty"
            } else if utf8::is_well_formed(candidate) {
                continue;
            } else {
                "is not well-formed UTF-8"
            };
            let detail = format!("candidate {position} of entry {index} {problem}");
            findings.push(Finding::new(AuditRule::Candidates, values_at(file, index), detail));
        }
    }
}
