#![cfg(feature = "android")]

use super::jvm::jni_throwing;
use crate::img::webp::{decode_next_frame, get_image_info};
use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jboolean, jint, jlong, jobject};
use jni_fn::jni_fn;
use libwebp_sys::WebPAnimDecoder;
use ndk::bitmap::Bitmap;
use std::ptr::copy_nonoverlapping;

#[jni_fn("com.mangareader.coil.AnimatedWebPDrawableKt")]
pub fn nativeDecodeNextFrame(
    mut env: JNIEnv,
    _: JClass,
    decoder: jlong,
    reset: jboolean,
    bitmap: jobject,
) -> jint {
    jni_throwing(&mut env, |env| {
        let dec = decoder as *mut WebPAnimDecoder;
        let (buf, timestamp) = unsafe { decode_next_frame(dec, reset != 0) };
        if !buf.is_null() {
            let info = unsafe { get_image_info(dec) };
            let handle = unsafe { Bitmap::from_jni(env.get_raw(), bitmap) };
            let dst = handle.lock_pixels()? as *mut u8;
            let size = info.canvas_width * info.canvas_height * 4;
            unsafe { copy_nonoverlapping(buf, dst, size as usize) };
            handle.unlock_pixels()?;
        }
        Ok(timestamp)
    })
}