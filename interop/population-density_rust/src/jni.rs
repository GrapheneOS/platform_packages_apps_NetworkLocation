//! Exposes the population density query engine through JNI.

use crate::database::{InitializationError, load_query_engine, query_engine};
use jni::{
    JNIEnv,
    objects::JClass,
    sys::{jboolean, jint, jlong},
};
use population_density::QueryEngine;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::OnceLock;

/// Stores the process-global query engine after successful initialization.
static QUERY_ENGINE: OnceLock<QueryEngine> = OnceLock::new();

/// Initializes the process-global query engine from a packaged database range.
///
/// The first successful initialization wins. The Kotlin caller serializes legitimate attempts and
/// always supplies the same immutable packaged resource.
///
/// # Safety
///
/// The JVM must provide valid JNI handles. `fd` must remain open and readable until this function
/// returns, and no process may modify or truncate its backing file while the process remains alive.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_app_grapheneos_populationdensity_PopulationDensityLocalDataSource_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    fd: jint,
    offset: jlong,
    length: jlong,
) -> jboolean {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if QUERY_ENGINE.get().is_some() {
            return Ok(());
        }

        // SAFETY: Kotlin keeps the descriptor open and the installed APK immutable.
        let engine = unsafe { load_query_engine(fd, offset, length) }?;
        // Kotlin serializes calls; a defensive race maps equivalent packaged bytes.
        let _ = QUERY_ENGINE.set(engine);
        Ok::<(), InitializationError>(())
    }));

    match result {
        Ok(Ok(())) => jni::sys::JNI_TRUE,
        Ok(Err(error)) => initialization_error(&mut env, error),
        Err(_) => initialization_error(&mut env, "population density native init panicked"),
    }
}

/// Returns the deepest qualifying population density ancestor for an S2 cell ID.
///
/// A failure throws `IllegalStateException` and returns zero. Android uses `panic=abort`, so device
/// correctness depends on explicit error returns rather than this host-only unwind firewall.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_grapheneos_populationdensity_PopulationDensityLocalDataSource_nativeQuery(
    mut env: JNIEnv,
    _class: JClass,
    s2_cell_id: jlong,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        query_engine(QUERY_ENGINE.get(), s2_cell_id as u64)
    }));

    match result {
        Ok(Ok(coarsened_s2_cell_id)) => coarsened_s2_cell_id as jlong,
        Ok(Err(error)) => query_error(&mut env, error),
        Err(_) => query_error(&mut env, "population density native query panicked"),
    }
}

/// Throws an initialization exception and returns `JNI_FALSE`.
fn initialization_error(env: &mut JNIEnv, error: impl ToString) -> jboolean {
    let _ = env.throw_new("java/io/IOException", error.to_string());
    jni::sys::JNI_FALSE
}

/// Throws a query exception and returns the invalid S2 cell sentinel.
fn query_error(env: &mut JNIEnv, error: impl ToString) -> jlong {
    let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
    0
}
