use crate::img::webp::{create_decoder, get_image_info, pack_image_info};
use anyhow::Result;
use jni::JNIEnv;
use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jboolean, jint, jlong, jobject};
use jni_fn::jni_fn;
use libwebp_sys::WebPAnimDecoder;

#[jni_fn("com.mangareader.coil.AnimatedWebPDrawableKt")]
pub fn nativeCreateDecoder(mut env: JNIEnv, _: JClass, buffer: JByteBuffer) -> jlong {
    jni_throwing(&mut env, |env| {
        let buffer = deref_mut_direct_bytebuffer(env, buffer)?;
        Ok(create_decoder(buffer) as i64)
    })
}

#[jni_fn("com.mangareader.coil.AnimatedWebPDrawableKt")]
pub fn nativeGetImageInfo(mut env: JNIEnv, _: JClass, decoder: jlong) -> jlong {
    jni_throwing(&mut env, |_| {
        let dec = decoder as *const WebPAnimDecoder;
        let info = unsafe { get_image_info(dec) };
        Ok(pack_image_info(info) as i64)
    })
}

#[jni_fn("com.mangareader.coil.AnimatedWebPDrawableKt")]
pub fn nativeDestroyDecoder(mut env: JNIEnv, _: JClass, decoder: jlong) {
    jni_throwing(&mut env, |_| {
        let dec = decoder as *mut WebPAnimDecoder;
        unsafe { WebPAnimDecoderDelete(dec) };
        Ok(())
    })
}

fn deref_mut_direct_bytebuffer<'local>(
    env: &JNIEnv,
    buffer: JByteBuffer<'local>,
) -> Result<&'local mut [u8]> {
    let ptr = env.get_direct_buffer_address(&buffer)?;
    let cap = env.get_direct_buffer_capacity(&buffer)?;
    Ok(unsafe { std::slice::from_raw_parts_mut(ptr, cap) })
}

pub(crate) fn jni_throwing<R>(
    env: &mut JNIEnv,
    f: impl FnOnce(&mut JNIEnv) -> Result<R>,
) -> R
where
    R: Default,
{
    match f(env) {
        Ok(v) => v,
        Err(e) => {
            let _ = env.throw_new("java/lang/RuntimeException", format!("{e:?}"));
            R::default()
        }
    }
}