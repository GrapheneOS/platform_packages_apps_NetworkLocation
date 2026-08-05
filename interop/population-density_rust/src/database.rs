//! Maps and queries the packaged population density database.

use population_density::QueryEngine;
use population_density::memmap2::{Mmap, MmapOptions};
use std::fmt::{self, Display, Formatter};
use std::fs::File;
use std::io;
use std::os::fd::BorrowedFd;

/// Describes a validated byte range for a database mapping.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct MappingRange {
    offset: u64,
    length: usize,
    end: u64,
}

/// Reports a database initialization failure.
#[derive(Debug)]
pub(crate) enum InitializationError {
    InvalidFileDescriptor(i32),
    NegativeOffset(i64),
    NonPositiveLength(i64),
    UnrepresentableLength(u64),
    RangeOverflow { offset: u64, length: u64 },
    DuplicateFileDescriptor(io::Error),
    InspectFileDescriptor(io::Error),
    NonRegularFile,
    RangeExceedsFile { end: u64, file_size: u64 },
    MemoryMap(io::Error),
    LoadQueryEngine(String),
}

impl Display for InitializationError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidFileDescriptor(fd) => {
                write!(formatter, "invalid database file descriptor: {fd}")
            }
            Self::NegativeOffset(offset) => {
                write!(formatter, "database offset must be non-negative: {offset}")
            }
            Self::NonPositiveLength(length) => {
                write!(formatter, "database length must be positive: {length}")
            }
            Self::UnrepresentableLength(length) => write!(
                formatter,
                "database length is not representable on this platform: {length}"
            ),
            Self::RangeOverflow { offset, length } => write!(
                formatter,
                "database byte range overflows: offset {offset}, length {length}"
            ),
            Self::DuplicateFileDescriptor(error) => {
                write!(
                    formatter,
                    "failed to duplicate database file descriptor: {error}"
                )
            }
            Self::InspectFileDescriptor(error) => {
                write!(
                    formatter,
                    "failed to inspect database file descriptor: {error}"
                )
            }
            Self::NonRegularFile => {
                write!(
                    formatter,
                    "database file descriptor does not refer to a regular file"
                )
            }
            Self::RangeExceedsFile { end, file_size } => write!(
                formatter,
                "database byte range exceeds file size: end {end}, size {file_size}"
            ),
            Self::MemoryMap(error) => {
                write!(formatter, "failed to memory-map database file: {error}")
            }
            Self::LoadQueryEngine(error) => write!(
                formatter,
                "failed to load query engine from memory-mapped database: {error}"
            ),
        }
    }
}

/// Reports a population density query failure.
#[derive(Debug)]
pub(crate) enum QueryError {
    Uninitialized,
    Query(String),
}

impl Display for QueryError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> fmt::Result {
        match self {
            Self::Uninitialized => write!(formatter, "query engine is not initialized"),
            Self::Query(error) => write!(formatter, "failed to query population density: {error}"),
        }
    }
}

/// Loads a query engine from a borrowed raw file descriptor.
///
/// The packaged range must contain the exact S2PD resource that passed strict and exhaustive
/// verification before packaging.
///
/// # Safety
///
/// `fd` must be open and readable until this function returns. No process may modify or truncate
/// the backing file while the returned engine remains alive.
pub(crate) unsafe fn load_query_engine(
    fd: i32,
    offset: i64,
    length: i64,
) -> Result<QueryEngine, InitializationError> {
    validate_file_descriptor(fd)?;

    // SAFETY: The caller keeps the descriptor valid for this call.
    let borrowed_fd = unsafe { BorrowedFd::borrow_raw(fd) };
    let owned_fd = borrowed_fd
        .try_clone_to_owned()
        .map_err(InitializationError::DuplicateFileDescriptor)?;
    let file = File::from(owned_fd);
    let mmap = map_database(&file, offset, length)?;

    QueryEngine::from_verified_mmap(mmap)
        .map_err(|error| InitializationError::LoadQueryEngine(error.to_string()))
}

/// Maps a validated range from a regular file.
fn map_database(file: &File, offset: i64, length: i64) -> Result<Mmap, InitializationError> {
    let mapping_range = validate_mapping_range(offset, length)?;
    let metadata = file
        .metadata()
        .map_err(InitializationError::InspectFileDescriptor)?;
    if !metadata.is_file() {
        return Err(InitializationError::NonRegularFile);
    }
    if mapping_range.end > metadata.len() {
        return Err(InitializationError::RangeExceedsFile {
            end: mapping_range.end,
            file_size: metadata.len(),
        });
    }

    // SAFETY: The checked range lies within a regular file. The caller of `load_query_engine`
    // guarantees immutable backing for the mapping lifetime; tests retain their temporary file.
    unsafe {
        MmapOptions::new()
            .offset(mapping_range.offset)
            .len(mapping_range.length)
            .map(file)
            .map_err(InitializationError::MemoryMap)
    }
}

/// Validates a raw file descriptor before it is borrowed.
fn validate_file_descriptor(fd: i32) -> Result<(), InitializationError> {
    if fd < 0 {
        return Err(InitializationError::InvalidFileDescriptor(fd));
    }
    Ok(())
}

/// Validates and converts a requested mapping range.
fn validate_mapping_range(offset: i64, length: i64) -> Result<MappingRange, InitializationError> {
    let offset = u64::try_from(offset).map_err(|_| InitializationError::NegativeOffset(offset))?;
    let length_u64 = match u64::try_from(length) {
        Ok(length) if length > 0 => length,
        _ => return Err(InitializationError::NonPositiveLength(length)),
    };
    let length = usize::try_from(length_u64)
        .map_err(|_| InitializationError::UnrepresentableLength(length_u64))?;
    let end = offset
        .checked_add(length_u64)
        .ok_or(InitializationError::RangeOverflow {
            offset,
            length: length_u64,
        })?;
    Ok(MappingRange {
        offset,
        length,
        end,
    })
}

/// Queries an initialized engine for a population density ancestor.
pub(crate) fn query_engine(
    engine: Option<&QueryEngine>,
    s2_cell_id: u64,
) -> Result<u64, QueryError> {
    engine
        .ok_or(QueryError::Uninitialized)?
        .query(s2_cell_id)
        .map_err(|error| QueryError::Query(error.to_string()))
}

#[cfg(test)]
mod tests {
    //! Exercises mapping validation without mutating process-global JNI state.

    use super::*;
    use std::fs::{OpenOptions, remove_file};
    use std::io::Write;
    use std::path::PathBuf;
    use std::process;

    /// Owns a deterministic temporary file and removes it when dropped.
    struct TemporaryFile {
        file: File,
        path: PathBuf,
    }

    impl TemporaryFile {
        /// Creates a deterministic temporary file containing `contents`.
        fn create(name: &str, contents: &[u8]) -> Self {
            let path = std::env::temp_dir().join(format!(
                "network_location_population_density_{name}_{}",
                process::id()
            ));
            let _ = remove_file(&path);
            let mut file = OpenOptions::new()
                .read(true)
                .write(true)
                .create_new(true)
                .open(&path)
                .expect("temporary test file should be created");
            file.write_all(contents)
                .expect("temporary test file should be written");
            Self { file, path }
        }
    }

    impl Drop for TemporaryFile {
        fn drop(&mut self) {
            let _ = remove_file(&self.path);
        }
    }

    /// Verifies that negative file descriptors are rejected.
    #[test]
    fn rejects_negative_file_descriptor() {
        assert!(matches!(
            validate_file_descriptor(-1),
            Err(InitializationError::InvalidFileDescriptor(-1))
        ));
    }

    /// Verifies that negative mapping offsets are rejected.
    #[test]
    fn rejects_negative_offset() {
        assert!(matches!(
            validate_mapping_range(-1, 1),
            Err(InitializationError::NegativeOffset(-1))
        ));
    }

    /// Verifies that zero and negative mapping lengths are rejected.
    #[test]
    fn rejects_non_positive_length() {
        for candidate_length in [0, -1] {
            assert!(matches!(
                validate_mapping_range(0, candidate_length),
                Err(InitializationError::NonPositiveLength(rejected_length))
                    if rejected_length == candidate_length
            ));
        }
    }

    /// Verifies that non-regular files are rejected.
    #[test]
    fn rejects_non_regular_file() {
        let directory =
            File::open(std::env::temp_dir()).expect("temporary directory should be readable");
        assert!(matches!(
            map_database(&directory, 0, 1),
            Err(InitializationError::NonRegularFile)
        ));
    }

    /// Verifies that mappings cannot extend past the end of a file.
    #[test]
    fn rejects_range_past_end_of_file() {
        let temporary_file = TemporaryFile::create("truncated", &[0; 4]);
        assert!(matches!(
            map_database(&temporary_file.file, 2, 3),
            Err(InitializationError::RangeExceedsFile {
                end: 5,
                file_size: 4
            })
        ));
    }

    /// Verifies that non-page-aligned resource offsets are mapped correctly.
    #[test]
    fn maps_unaligned_subrange() {
        let temporary_file = TemporaryFile::create("unaligned", &[9, 1, 2, 3, 8]);
        let mmap =
            map_database(&temporary_file.file, 1, 3).expect("unaligned subrange should be mapped");
        assert_eq!(&mmap[..], &[1, 2, 3]);
    }

    /// Verifies that a query requires an initialized engine.
    #[test]
    fn rejects_query_without_initialized_engine() {
        assert!(matches!(
            query_engine(None, 1),
            Err(QueryError::Uninitialized)
        ));
    }
}
