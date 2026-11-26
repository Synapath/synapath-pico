package com.picoxr.capturesdkdemo

import com.pxr.capturelib.PXRCharacteristics
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Preserve each mode's actual SDK callback; never substitute an FOV-derived K. */
object CalibrationSnapshot {
    private fun packed(bytes: ByteArray?): JSONObject {
        val values = JSONArray()
        val aligned = bytes != null && bytes.isNotEmpty() && bytes.size % 8 == 0
        var finite = aligned
        if (aligned) {
            val buffer = ByteBuffer.wrap(bytes!!).order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.hasRemaining()) {
                val value = buffer.double
                if (value.isFinite()) values.put(value) else { values.put(JSONObject.NULL); finite = false }
            }
        }
        return JSONObject().put("available", bytes != null).put("byte_count", bytes?.size ?: 0)
            .put("base64", bytes?.let { Base64.getEncoder().encodeToString(it) } ?: JSONObject.NULL)
            .put("encoding", "little_endian_float64").put("decode_valid", aligned && finite)
            .put("packed_values", values)
            .put("layout", "vendor_packed_cameras; original bytes preserved")
    }

    fun characteristics(c: PXRCharacteristics, undistorted: Boolean, width: Int, height: Int): JSONObject {
        val result = JSONObject()
            .put("source", "captureOnCharacteristicsCallback / LENS_RGB_*_CALIBRATION")
            .put("image_mode", if (undistorted) "sdk_undistorted_spatial_video" else "sdk_camera_raw")
            .put("canvas_width", width).put("canvas_height", height)
            .put("per_eye_width", width / 2).put("per_eye_height", height)
            .put("camera_count", c.get(PXRCharacteristics.LENS_RGB_CAMERA_CNT) ?: JSONObject.NULL)
            .put("camera_model", c.get(PXRCharacteristics.LENS_RGB_CAMERA_MODEL_CALIBRATION) ?: JSONObject.NULL)
            .put("distortion_model", c.get(PXRCharacteristics.LENS_RGB_DISTORTION_MODEL) ?: JSONObject.NULL)
            .put("intrinsics", packed(c.get(PXRCharacteristics.LENS_RGB_CAMERA_INTRINSICS_CALIBRATION)))
            .put("extrinsics", packed(c.get(PXRCharacteristics.LENS_RGB_CAMERA_EXTRINSICS_CALIBRATION)))
            .put("distortion_coefficients", packed(c.get(PXRCharacteristics.LENS_RGB_DISTORTION_COEFFICIENTS)))
            .put("reference_width", c.get(PXRCharacteristics.ORIGIN_CAMERA_WIDTH) ?: JSONObject.NULL)
            .put("reference_height", c.get(PXRCharacteristics.ORIGIN_CAMERA_HEIGHT) ?: JSONObject.NULL)
            .put("reference_resolution_semantics", "SDK RAW-mode calibration is scaled to requested per-eye output; not full sensor resolution")
            .put("projection_match_verified", false)
            .put("captured_at_unix_ms", System.currentTimeMillis())
        result.put("available", listOf("intrinsics", "extrinsics").all {
            result.getJSONObject(it).optBoolean("decode_valid")
        })
        val values = result.getJSONObject("intrinsics").getJSONArray("packed_values")
        val count = result.optInt("camera_count", 0)
        if (count == 2 && values.length() == 8 && result.getJSONObject("intrinsics").optBoolean("decode_valid")) {
            val cameras = JSONArray()
            for (i in 0 until count) {
                // Confirmed against this firmware's parseSpatialParams/parseOriginParams and mode A/B samples.
                val fx = values.getDouble(i * 4); val fy = values.getDouble(i * 4 + 1)
                val cx = values.getDouble(i * 4 + 2); val cy = values.getDouble(i * 4 + 3)
                cameras.put(JSONObject().put("packed_camera_index", i)
                    .put("fx", fx).put("fy", fy).put("cx", cx).put("cy", cy)
                    .put("K", JSONArray(listOf(listOf(fx, 0.0, cx), listOf(0.0, fy, cy), listOf(0.0, 0.0, 1.0)))))
            }
            result.put("intrinsic_field_order", "fx,fy,cx,cy per packed camera; observed on firmware 5.15.8")
                .put("camera_order_note", "SDK packed order; physical left/right mapping not independently verified")
                .put("cameras", cameras)
        }
        return result
    }

    fun output(calibration: JSONObject?, undistorted: Boolean, width: Int, height: Int): JSONObject {
        val output = calibration?.let { JSONObject(it.toString()) }
            ?: JSONObject().put("available", false).put("status", "SDK callback unavailable")
        return output.put("image_mode", if (undistorted) "sdk_undistorted_spatial_video" else "sdk_camera_raw")
            .put("stereo_layout", "side_by_side").put("canvas_width", width).put("canvas_height", height)
            .put("per_eye_width", width / 2).put("per_eye_height", height)
            .put("projection_match_verified", false)
            .put("parameter_source", "mode_specific_SDK_callback; not FOV helper")
            .put("note", "Vendor-reported parameters for the configured mode; independent physical projection validation remains outstanding")
    }
}
