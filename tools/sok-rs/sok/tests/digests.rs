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

//! The canonical digests of the fixtures, pinned: the stream definition is
//! normative (docs/design/sok-rs.md §5), so any change to how it is built
//! must show up here. The pinned values were computed by an independent
//! Python reading of the stream definition; the Kotlin conformance tests prove
//! the same stream on the packaged dictionaries and the fuzz corpus.

mod common;

use sok::SokFile;
use sok::digest::{Fnv1a64, digest, hex};

fn digest_of(name: &str) -> String {
    let bytes = common::fixture(name);
    hex(digest(&SokFile::parse(&bytes).expect("accepted")))
}

#[test]
fn the_empty_dictionary_digests_two_zero_words() {
    let mut hash = Fnv1a64::new();
    hash.write(&[0; 8]);
    assert_eq!(hex(hash.finish()), digest_of("empty.sok"));
}

#[test]
fn the_prefixes_stream_is_spelled_out() {
    let mut hash = Fnv1a64::new();
    hash.write(&3u32.to_le_bytes());
    hash.write(&3u32.to_le_bytes());
    for (key, candidate) in [(&b"a"[..], &b"A"[..]), (b"ab", b"AB"), (b"abc", b"ABC")] {
        hash.write(&u32::try_from(key.len()).expect("short").to_le_bytes());
        hash.write(key);
        hash.write(&[1]);
        hash.write(&u16::try_from(candidate.len()).expect("short").to_le_bytes());
        hash.write(candidate);
    }
    assert_eq!(hex(hash.finish()), digest_of("prefixes.sok"));
}

#[test]
fn fixture_digests_are_pinned() {
    let pinned = [
        ("empty.sok", DIGEST_EMPTY),
        ("many-candidates.sok", DIGEST_MANY_CANDIDATES),
        ("prefixes.sok", DIGEST_PREFIXES),
        ("widths.sok", DIGEST_WIDTHS),
    ];
    for (name, expected) in pinned {
        assert_eq!(expected, digest_of(name), "{name}");
    }
}

const DIGEST_EMPTY: &str = "a8c7f832281a39c5";
const DIGEST_MANY_CANDIDATES: &str = "485b319d0ddcff85";
const DIGEST_PREFIXES: &str = "d48af38172a39280";
const DIGEST_WIDTHS: &str = "257f553a9f91686e";
