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

//! Well-formed UTF-8, read straight off the byte-range table of RFC 3629 §4
//! (the Unicode Standard's Table 3-7). Written from the table, not from the
//! Kotlin `Utf8` object, so the two readers do not share a misreading.

use std::ops::RangeInclusive;

const CONTINUATION: RangeInclusive<u8> = 0x80..=0xBF;

/// Length of the well-formed sequence that starts `bytes`, or `None` when
/// the sequence is malformed or truncated by the end of `bytes`.
fn sequence_length(bytes: &[u8]) -> Option<usize> {
    let (&lead, rest) = bytes.split_first()?;
    let (length, second) = match lead {
        0x00..=0x7F => return Some(1),
        0xC2..=0xDF => (2, CONTINUATION),
        0xE0 => (3, 0xA0..=0xBF),
        0xE1..=0xEC | 0xEE..=0xEF => (3, CONTINUATION),
        0xED => (3, 0x80..=0x9F),
        0xF0 => (4, 0x90..=0xBF),
        0xF1..=0xF3 => (4, CONTINUATION),
        0xF4 => (4, 0x80..=0x8F),
        _ => return None,
    };
    let (first, others) = rest.get(..length - 1)?.split_first()?;
    (second.contains(first) && others.iter().all(|byte| CONTINUATION.contains(byte))).then_some(length)
}

/// Whether `bytes` is well-formed UTF-8: no overlong form, no encoded
/// surrogate, nothing above U+10FFFF, no truncated sequence.
#[must_use]
pub fn is_well_formed(mut bytes: &[u8]) -> bool {
    while !bytes.is_empty() {
        match sequence_length(bytes).and_then(|length| bytes.get(length..)) {
            Some(rest) => bytes = rest,
            None => return false,
        }
    }
    true
}

/// The first scalar value of `bytes` and the length of its encoding, or
/// `None` when `bytes` is empty or does not start with a well-formed sequence.
#[must_use]
pub fn decode_first(bytes: &[u8]) -> Option<(char, usize)> {
    let length = sequence_length(bytes)?;
    let encoded = bytes.get(..length)?;
    let mut value = match length {
        1 => u32::from(*encoded.first()?),
        2 => u32::from(encoded.first()? & 0x1F),
        3 => u32::from(encoded.first()? & 0x0F),
        _ => u32::from(encoded.first()? & 0x07),
    };
    for byte in encoded.get(1..)? {
        value = (value << 6) | u32::from(byte & 0x3F);
    }
    Some((char::from_u32(value)?, length))
}

/// UTF-16 code units of a well-formed region: two for every supplementary
/// scalar value, one for every other; `None` when the region is not
/// well-formed. This is the unit of the format's `maxKeyLength` (invariant I1).
#[must_use]
pub fn utf16_length(mut bytes: &[u8]) -> Option<usize> {
    let mut units = 0usize;
    while !bytes.is_empty() {
        let (scalar, length) = decode_first(bytes)?;
        units += scalar.len_utf16();
        bytes = bytes.get(length..)?;
    }
    Some(units)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A small deterministic generator, so the tests need no dependency.
    struct XorShift(u64);

    impl XorShift {
        fn next(&mut self) -> u64 {
            self.0 ^= self.0 << 13;
            self.0 ^= self.0 >> 7;
            self.0 ^= self.0 << 17;
            self.0
        }

        fn byte(&mut self) -> u8 {
            self.next().to_le_bytes()[0]
        }
    }

    fn assert_agrees_with_std(bytes: &[u8]) {
        assert_eq!(
            std::str::from_utf8(bytes).is_ok(),
            is_well_formed(bytes),
            "{bytes:02x?}"
        );
    }

    #[test]
    fn every_one_and_two_byte_input_agrees_with_std() {
        for b0 in 0..=u8::MAX {
            assert_agrees_with_std(&[b0]);
            for b1 in 0..=u8::MAX {
                assert_agrees_with_std(&[b0, b1]);
            }
        }
    }

    #[test]
    fn every_lead_with_boundary_continuations_agrees_with_std() {
        let boundaries = [0x00, 0x7F, 0x80, 0x8F, 0x90, 0x9F, 0xA0, 0xBF, 0xC0, 0xFF];
        for b0 in 0..=u8::MAX {
            for b1 in boundaries {
                for b2 in boundaries {
                    assert_agrees_with_std(&[b0, b1, b2]);
                    for b3 in boundaries {
                        assert_agrees_with_std(&[b0, b1, b2, b3]);
                    }
                }
            }
        }
    }

    #[test]
    fn random_byte_strings_agree_with_std() {
        let mut random = XorShift(0x2026_1016_5eed);
        for _ in 0..50_000 {
            let length = usize::from(random.byte() % 12);
            let bytes: Vec<u8> = (0..length).map(|_| random.byte()).collect();
            assert_agrees_with_std(&bytes);
        }
    }

    #[test]
    fn decoding_agrees_with_std_on_every_scalar_value() {
        for scalar in (0..=0x10_FFFF).filter_map(char::from_u32) {
            let mut buffer = [0u8; 4];
            let encoded = scalar.encode_utf8(&mut buffer).as_bytes();
            assert_eq!(
                Some((scalar, encoded.len())),
                decode_first(encoded),
                "U+{:04X}",
                u32::from(scalar)
            );
            assert_eq!(
                Some(scalar.len_utf16()),
                utf16_length(encoded),
                "U+{:04X}",
                u32::from(scalar)
            );
        }
    }

    #[test]
    fn decoding_rejects_what_is_not_well_formed() {
        for malformed in [
            &[][..],
            &[0x80],
            &[0xC0, 0x80],
            &[0xE4, 0xB8],
            &[0xED, 0xA0, 0x80],
            &[0xF4, 0x90, 0x80, 0x80],
        ] {
            assert_eq!(None, decode_first(malformed), "{malformed:02x?}");
        }
        assert_eq!(None, utf16_length(&[0x61, 0xFF]));
        assert_eq!(Some(0), utf16_length(&[]));
        assert_eq!(Some(4), utf16_length("a中𠀀".as_bytes()));
    }

    #[test]
    fn a_replacement_character_is_well_formed() {
        assert!(is_well_formed("\u{FFFD}".as_bytes()));
        assert_eq!(Some(('\u{FFFD}', 3)), decode_first("\u{FFFD}".as_bytes()));
    }
}
