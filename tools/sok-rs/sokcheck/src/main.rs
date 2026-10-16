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

//! `sokcheck`: the command line of the independent `.sok` reader.
//!
//! Exit status: 0 when every check passes, 1 when a file violates the format
//! or a comparison disagrees, 2 on a usage or I/O error.

use std::process::ExitCode;

fn main() -> ExitCode {
    eprintln!("usage: sokcheck <command> ...");
    ExitCode::from(2)
}
