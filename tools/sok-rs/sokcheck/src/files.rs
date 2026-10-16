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

//! Reading the files a command works on.

use std::fs;
use std::path::Path;

use crate::Failure;

/// A file's name as reported, and its bytes.
pub struct SokInput {
    pub name: String,
    pub bytes: Vec<u8>,
}

/// A file as given, or every `.sok` file directly inside a directory, in
/// name order so the output does not depend on the file system.
pub fn read_path(path: &Path) -> Result<Vec<SokInput>, Failure> {
    if path.is_dir() {
        read_directory(path)
    } else {
        Ok(vec![read_file(path)?])
    }
}

/// Every `.sok` file directly inside `directory`, in name order.
pub fn read_directory(directory: &Path) -> Result<Vec<SokInput>, Failure> {
    let listing =
        fs::read_dir(directory).map_err(|error| Failure::Error(format!("{}: {error}", directory.display())))?;
    let mut paths = Vec::new();
    for entry in listing {
        let path = entry
            .map_err(|error| Failure::Error(format!("{}: {error}", directory.display())))?
            .path();
        if path.is_file() && path.extension().is_some_and(|extension| extension == "sok") {
            paths.push(path);
        }
    }
    if paths.is_empty() {
        return Err(Failure::Error(format!("{}: no .sok file", directory.display())));
    }
    paths.sort();
    paths.iter().map(|path| read_file(path)).collect()
}

/// One file, named by its file name.
pub fn read_file(path: &Path) -> Result<SokInput, Failure> {
    let bytes = fs::read(path).map_err(|error| Failure::Error(format!("{}: {error}", path.display())))?;
    let name = path.file_name().map_or_else(
        || path.display().to_string(),
        |name| name.to_string_lossy().into_owned(),
    );
    Ok(SokInput { name, bytes })
}
