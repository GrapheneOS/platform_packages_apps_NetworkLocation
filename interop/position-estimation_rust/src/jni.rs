//! Documentation for jni module.

use std::ptr::null_mut;

use android_logger::Config;
use jni::{
    objects::{JClass, JObjectArray},
    sys::jobject,
    JNIEnv,
};
use log::debug;

use crate::{
    coordinate::Coordinate, estimate_position, measurement::Measurement, position::Position,
};

// SAFETY: There is no other global function of this name.
#[no_mangle]
pub extern "system" fn Java_app_grapheneos_networklocation_interop_position_1estimation_PositionEstimation_estimatePosition(
    mut env: JNIEnv,
    _class: JClass,
    measurements: JObjectArray,
) -> jobject {
    android_logger::init_once(
        Config::default()
            // TODO: remove with_max_level once android_logger 0.15.0 or newer ships,
            // which allows delegating log filtering to liblog when the android-api-30
            // feature is enabled
            .with_max_level(log::LevelFilter::Info)
            .with_tag("PositionEstimation"),
    );

    debug!("starting");

    let len = env
        .get_array_length(&measurements)
        .expect("should be able to get measurements length")
        .try_into()
        .expect("measurements length should be within usize");

    debug!("number of measurements: {}", len);

    let mut measurements_converted = Vec::with_capacity(len);

    let measurement_class_path =
        "app/grapheneos/networklocation/interop/position_estimation/Measurement";
    let position_class_path = "app/grapheneos/networklocation/interop/position_estimation/Position";
    let coordinate_class_path =
        "app/grapheneos/networklocation/interop/position_estimation/Coordinate";

    let measurement_position_field_id = env
        .get_field_id(
            measurement_class_path,
            "position",
            format!("L{};", position_class_path),
        )
        .expect("should be able to get measurement class' position field id");

    let position_coords_signature = format!("L{};", coordinate_class_path);
    let position_x_field_id = env
        .get_field_id(position_class_path, "x", &position_coords_signature)
        .expect("should be able to get position class' x field id");
    let position_y_field_id = env
        .get_field_id(position_class_path, "y", &position_coords_signature)
        .expect("should be able to get position class' y field id");
    let position_z_field_id = env
        .get_field_id(position_class_path, "z", position_coords_signature)
        .expect("should be able to get position class' z field id");

    let coordinate_real_field_id = env
        .get_field_id(coordinate_class_path, "real", "Z")
        .expect("should be able to get coordinate class' real field id");
    let coordinate_value_field_id = env
        .get_field_id(coordinate_class_path, "value", "D")
        .expect("should be able to get coordinate class' value field id");
    let coordinate_variance_field_id = env
        .get_field_id(coordinate_class_path, "variance", "D")
        .expect("should be able to get coordinate class' variance field id");

    let measurement_distance_field_id = env
        .get_field_id(measurement_class_path, "distance", "D")
        .expect("should be able to get measurement class' distance field id");
    let measurement_probability_field_id = env
        .get_field_id(measurement_class_path, "probability", "D")
        .expect("should be able to get measurement class' probability field id");

    for index in 0..len {
        let measurement_obj = env
            .get_object_array_element(
                &measurements,
                index.try_into().expect("index should fit i32"),
            )
            .expect("should be able to get measurement");

        let position_obj = env
            .get_field_unchecked(
                &measurement_obj,
                measurement_position_field_id,
                jni::signature::ReturnType::Object,
            )
            .expect("should be able to get position field from measurement")
            .l()
            .expect("position should be an object");

        let x_obj = env
            .get_field_unchecked(
                &position_obj,
                position_x_field_id,
                jni::signature::ReturnType::Object,
            )
            .expect("should be able to get x from position")
            .l()
            .expect("x should be an object");
        let y_obj = env
            .get_field_unchecked(
                &position_obj,
                position_y_field_id,
                jni::signature::ReturnType::Object,
            )
            .expect("should be able to get y from position")
            .l()
            .expect("y should be an object");
        let z_obj = env
            .get_field_unchecked(
                &position_obj,
                position_z_field_id,
                jni::signature::ReturnType::Object,
            )
            .expect("should be able to get z from position")
            .l()
            .expect("z should be an object");

        let measurement = Measurement {
            position: Position {
                x: Coordinate {
                    real: env
                        .get_field_unchecked(
                            &x_obj,
                            coordinate_real_field_id,
                            jni::signature::ReturnType::Primitive(
                                jni::signature::Primitive::Boolean,
                            ),
                        )
                        .expect("should be able to get real from x")
                        .z()
                        .expect("real should be a boolean"),
                    value: env
                        .get_field_unchecked(
                            &x_obj,
                            coordinate_value_field_id,
                            jni::signature::ReturnType::Primitive(
                                jni::signature::Primitive::Double,
                            ),
                        )
                        .expect("should be able to get value from x")
                        .d()
                        .expect("value should be double"),
                    variance: env
                        .get_field_unchecked(
                            &x_obj,
                            coordinate_variance_field_id,
                            jni::signature::ReturnType::Primitive(
                                jni::signature::Primitive::Double,
                            ),
                        )
                        .expect("should be able to get variance from x")
                        .d()
                        .expect("variance should be a double"),
                },
                y: Coordinate {
                    real: env
                        .get_field_unchecked(
                            &y_obj,
                            coordinate_real_field_id,
                            jni::signature::ReturnType::Primitive(
                                jni::signature::Primitive::Boolean,
                            ),
                        )
                        .expect("should be able to get real from y")
                        .z()
                        .expect("real should be a boolean"),
                    value: env
                        .get_field_unchecked(
                            &y_obj,
                            coordinate_value_field_id,
                            jni::signature::ReturnType::Primitive(
                                jni::signature::Primitive::Double,
                            ),
                        )
                        .expect("should be able to get value from y")
                        .d()
                        .expect("value should be double"),
                    variance: env
                        .get_field_unchecked(
                            &y_obj,
                            coordinate_variance_field_id,
                            jni::signature::ReturnType::Primitive(
                                jni::signature::Primitive::Double,
                            ),
                        )
                        .expect("should be able to get variance from y")
                        .d()
                        .expect("variance should be a double"),
                },
                z: Coordinate {
                    real: env
                        .get_field_unchecked(
                            &z_obj,
                            coordinate_real_field_id,
                            jni::signature::ReturnType::Primitive(
                                jni::signature::Primitive::Boolean,
                            ),
                        )
                        .expect("should be able to get real from z")
                        .z()
                        .expect("real should be a boolean"),
                    value: env
                        .get_field_unchecked(
                            &z_obj,
                            coordinate_value_field_id,
                            jni::signature::ReturnType::Primitive(
                                jni::signature::Primitive::Double,
                            ),
                        )
                        .expect("should be able to get value from z")
                        .d()
                        .expect("value should be double"),
                    variance: env
                        .get_field_unchecked(
                            &z_obj,
                            coordinate_variance_field_id,
                            jni::signature::ReturnType::Primitive(
                                jni::signature::Primitive::Double,
                            ),
                        )
                        .expect("should be able to get variance from z")
                        .d()
                        .expect("variance should be a double"),
                },
            },
            distance: env
                .get_field_unchecked(
                    &measurement_obj,
                    measurement_distance_field_id,
                    jni::signature::ReturnType::Primitive(jni::signature::Primitive::Double),
                )
                .expect("should be able to get distance from measurement")
                .d()
                .expect("distance should be a double"),
            probability: env
                .get_field_unchecked(
                    &measurement_obj,
                    measurement_probability_field_id,
                    jni::signature::ReturnType::Primitive(jni::signature::Primitive::Double),
                )
                .expect("should be able to get probability from measurement")
                .d()
                .expect("probability should be a double"),
        };

        debug!(
            "measurement at index {} has distance: {}",
            index, measurement.distance
        );

        measurements_converted.push(measurement);
    }

    let estimated_position = estimate_position(&mut measurements_converted);

    match estimated_position {
        Some(estimated_position) => {
            let position = estimated_position.position;
            let _inliers_size = estimated_position.inliers_size;

            let position_class = env
                .find_class(position_class_path)
                .expect("should be able to find position class");

            let coordinate_class_signature = "(ZDD)V";
            let x_obj = env
                .new_object(
                    coordinate_class_path,
                    coordinate_class_signature,
                    &[
                        position.x.real.into(),
                        position.x.value.into(),
                        position.x.variance.into(),
                    ],
                )
                .expect("should be able to create new x coordinate object");
            let y_obj = env
                .new_object(
                    coordinate_class_path,
                    coordinate_class_signature,
                    &[
                        position.y.real.into(),
                        position.y.value.into(),
                        position.y.variance.into(),
                    ],
                )
                .expect("should be able to create new y coordinate object");
            let z_obj = env
                .new_object(
                    coordinate_class_path,
                    coordinate_class_signature,
                    &[
                        position.z.real.into(),
                        position.z.value.into(),
                        position.z.variance.into(),
                    ],
                )
                .expect("should be able to create new z coordinate object");

            let return_obj = env
                .new_object(
                    position_class,
                    format!(
                        "(L{};L{};L{};)V",
                        coordinate_class_path, coordinate_class_path, coordinate_class_path
                    ),
                    &[(&x_obj).into(), (&y_obj).into(), (&z_obj).into()],
                )
                .expect("should be able to create return position object");

            return_obj.into_raw()
        }
        None => null_mut(),
    }
}
