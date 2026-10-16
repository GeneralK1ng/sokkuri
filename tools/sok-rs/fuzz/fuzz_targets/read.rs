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

//! Any bytes through the reader (docs/design/sok-rs.md §7). The reader must
//! never panic, and an accepted file must be readable to the end: every entry,
//! every candidate, the audit and the digest. Safe Rust panics on any
//! out-of-bounds access, so "accepted means in bounds" is checked by running
//! these. The inputs this target keeps become the conformance corpus that the
//! Kotlin decoder replays on every platform.

#![no_main]

use libfuzzer_sys::fuzz_target;
use sok::SokFile;

fuzz_target!(|data: &[u8]| {
    let Ok(file) = SokFile::parse(data) else {
        return;
    };
    let mut entries = 0usize;
    for entry in file.entries() {
        let candidates = entry.candidates().count();
        assert_eq!(usize::from(entry.candidate_count()), candidates, "an accepted entry reads all its candidates");
        entries += 1;
    }
    assert_eq!(file.len(), entries, "an accepted file reads all its entries");
    std::hint::black_box(sok::audit::audit(&file));
    std::hint::black_box(sok::digest::digest(&file));
});
