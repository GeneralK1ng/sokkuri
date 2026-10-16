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

//! Every packaged dictionary is accepted, and every entry and candidate of it
//! can be read back.

mod common;

use sok::SokFile;

#[test]
fn every_packaged_dictionary_is_accepted_and_fully_readable() {
    let files = common::packaged();
    assert_eq!(
        22,
        files.len(),
        "packaged dictionaries: {:?}",
        files.iter().map(|(name, _)| name).collect::<Vec<_>>()
    );
    for (name, bytes) in &files {
        let file = SokFile::parse(bytes).unwrap_or_else(|violation| panic!("{name}: {violation}"));
        assert!(!file.is_empty(), "{name} has no entry");
        let mut entries = 0usize;
        for entry in file.entries() {
            assert_eq!(
                usize::from(entry.candidate_count()),
                entry.candidates().count(),
                "{name}"
            );
            entries += 1;
        }
        assert_eq!(file.len(), entries, "{name}");
    }
}
