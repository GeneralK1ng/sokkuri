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

//! Paths and readers shared by the integration tests.

#![allow(dead_code, clippy::indexing_slicing)]

use std::fs;
use std::path::PathBuf;

/// The repository root: `SOKKURI_REPOSITORY` when set, otherwise three levels
/// above this crate. The variable is for runs from a copy of the workspace,
/// such as `cargo mutants`, whose copy holds no packaged dictionaries.
pub fn repository() -> PathBuf {
    std::env::var_os("SOKKURI_REPOSITORY").map_or_else(
        || PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../.."),
        PathBuf::from,
    )
}

/// The encoder-written fixtures, pinned by the Kotlin `SokFixtureFilesTest`;
/// they live in this workspace, so a copy of it carries them.
pub fn fixture(name: &str) -> Vec<u8> {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../fixtures").join(name);
    fs::read(&path).unwrap_or_else(|error| panic!("{}: {error}", path.display()))
}

/// The packaged dictionaries, sorted by file name.
pub fn packaged() -> Vec<(String, Vec<u8>)> {
    let directory = repository().join("sokkuri-runtime/src/commonMain/resources/dictionary");
    let mut files: Vec<(String, Vec<u8>)> = fs::read_dir(&directory)
        .unwrap_or_else(|error| panic!("{}: {error}", directory.display()))
        .map(|entry| entry.expect("directory entry").path())
        .filter(|path| path.extension().is_some_and(|extension| extension == "sok"))
        .map(|path| {
            (
                path.file_name().expect("file name").to_string_lossy().into_owned(),
                fs::read(&path).expect("read"),
            )
        })
        .collect();
    files.sort();
    files
}

/// A little-endian u32 written at `at`, for building corrupt copies.
pub fn put_u32(bytes: &mut [u8], at: usize, value: u32) {
    bytes[at..at + 4].copy_from_slice(&value.to_le_bytes());
}

/// A little-endian u16 written at `at`.
pub fn put_u16(bytes: &mut [u8], at: usize, value: u16) {
    bytes[at..at + 2].copy_from_slice(&value.to_le_bytes());
}

/// A little-endian u32 read at `at`.
pub fn get_u32(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(bytes[at..at + 4].try_into().expect("four bytes"))
}
