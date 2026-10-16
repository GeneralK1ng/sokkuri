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

//! The audit rules of docs/verification/sok-reader.md: what the specification
//! states and the encoder guarantees, but the runtime decoder does not check.
//! An accepted file can break them; the audit says which, and never changes
//! whether the file is accepted. A rule the decoder came to enforce moved to
//! the acceptance rules, and its identifier is not reused: A1, key order, is
//! V12; A2, an exact `maxKeyLength`, is V5; A3, offset tables starting at 0,
//! is part of V7 and V9; and A4, the reserved field, is part of V4.

use std::fmt;

use crate::reader::SokFile;
use crate::utf8;

/// An audit rule (docs/verification/sok-reader.md).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
#[non_exhaustive]
pub enum AuditRule {
    /// A5: every candidate is non-empty and well-formed UTF-8.
    Candidates,
}

impl AuditRule {
    /// Every rule, in the order the audit checks them.
    pub const ALL: [Self; 1] = [Self::Candidates];

    /// The rule's stable identifier, never reused for another rule.
    #[must_use]
    pub const fn id(self) -> &'static str {
        match self {
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

/// Where entry `index`'s candidates start in the file.
fn values_at(file: &SokFile<'_>, index: usize) -> usize {
    file.value_blob_offset() + file.value_offset(index).unwrap_or_default()
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
