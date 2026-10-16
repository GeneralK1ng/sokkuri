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

//! The canonical digest of docs/verification/sok-reader.md: how two readers show they
//! split a file into the same entries, without trusting each other's code.
//!
//! It is FNV-1a 64 over this byte stream, built from the stored bytes, never
//! from decoded text, so the readers' different handling of malformed values
//! cannot show up as a structural difference:
//!
//! ```text
//! u32le entryCount
//! u32le maxKeyLength
//! per entry, in table order:
//!     u32le key byte length, key bytes
//!     u8    candidate count
//!     per candidate: u16le byte length, candidate bytes
//! ```
//!
//! The Kotlin side computes the same stream in its conformance tests.

use crate::reader::SokFile;

/// FNV-1a with a 64-bit state.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Fnv1a64(u64);

impl Fnv1a64 {
    /// The FNV-1a 64 offset basis.
    pub const OFFSET_BASIS: u64 = 0xcbf2_9ce4_8422_2325;

    /// The FNV-1a 64 prime.
    pub const PRIME: u64 = 0x0000_0100_0000_01b3;

    /// A hash of no input.
    #[must_use]
    pub const fn new() -> Self {
        Self(Self::OFFSET_BASIS)
    }

    /// Absorbs `bytes`.
    pub fn write(&mut self, bytes: &[u8]) {
        for &byte in bytes {
            self.0 = (self.0 ^ u64::from(byte)).wrapping_mul(Self::PRIME);
        }
    }

    /// The hash of everything absorbed so far.
    #[must_use]
    pub const fn finish(self) -> u64 {
        self.0
    }
}

impl Default for Fnv1a64 {
    fn default() -> Self {
        Self::new()
    }
}

/// The canonical digest of an accepted file.
#[must_use]
pub fn digest(file: &SokFile<'_>) -> u64 {
    let header = file.header();
    let mut hash = Fnv1a64::new();
    hash.write(&header.entry_count.to_le_bytes());
    hash.write(&header.max_key_length.to_le_bytes());
    for entry in file.entries() {
        let key = entry.key();
        // Both conversions are exact: a key is no longer than the key blob,
        // whose size is a u32, and a candidate's length was read from a u16.
        hash.write(&u32::try_from(key.len()).unwrap_or(u32::MAX).to_le_bytes());
        hash.write(key);
        hash.write(&[entry.candidate_count()]);
        for candidate in entry.candidates() {
            hash.write(&u16::try_from(candidate.len()).unwrap_or(u16::MAX).to_le_bytes());
            hash.write(candidate);
        }
    }
    hash.finish()
}

/// A digest as the 16 lowercase hexadecimal digits the expectation files use.
#[must_use]
pub fn hex(value: u64) -> String {
    format!("{value:016x}")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fnv(bytes: &[u8]) -> u64 {
        let mut hash = Fnv1a64::new();
        hash.write(bytes);
        hash.finish()
    }

    /// The published FNV-1a 64 test vectors.
    #[test]
    fn fnv1a64_matches_the_reference_vectors() {
        assert_eq!(0xcbf2_9ce4_8422_2325, fnv(b""));
        assert_eq!(0xaf63_dc4c_8601_ec8c, fnv(b"a"));
        assert_eq!(0x8594_4171_f739_67e8, fnv(b"foobar"));
    }

    #[test]
    fn writing_in_pieces_equals_writing_at_once() {
        let mut pieces = Fnv1a64::default();
        pieces.write(b"foo");
        pieces.write(b"");
        pieces.write(b"bar");
        assert_eq!(fnv(b"foobar"), pieces.finish());
    }

    #[test]
    fn hex_is_sixteen_lowercase_digits() {
        assert_eq!("000000000000002a", hex(42));
        assert_eq!("cbf29ce484222325", hex(Fnv1a64::OFFSET_BASIS));
    }
}
