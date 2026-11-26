#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <atomic>
#include <array>
#include <chrono>
#include <cstring>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include <cmath>

namespace {
constexpr int WIDTH=1600, HEIGHT=900;
constexpr float QUAD_WIDTH=1.6f, QUAD_HEIGHT=.9f, QUAD_Z=-1.8f;
std::atomic<bool> stopRequested{false};
void check(XrResult r, const char* op) {
    if (XR_FAILED(r)) throw std::runtime_error(std::string(op) + " failed: " + std::to_string(r));
}
struct Runtime {
    JNIEnv* env; jobject activity; jobject androidActivity{}; jmethodID status, glReady, glFrame, bitmapMethod, shutdown;
    XrInstance instance{}; XrSession session{}; XrSpace view{}, local{}; XrSwapchain swapchain{};
    XrActionSet actions{}; XrAction select{}, trigger{}, aim{};
    std::array<XrPath, 2> hands{}; std::array<XrSpace, 2> aimSpaces{};
    XrPassthroughFB passthrough{}; XrPassthroughLayerFB passthroughLayer{};
    PFN_xrDestroyPassthroughFB destroyPassthrough{};
    PFN_xrDestroyPassthroughLayerFB destroyPassthroughLayer{};
    EGLDisplay display = EGL_NO_DISPLAY; EGLContext context = EGL_NO_CONTEXT;
    EGLSurface pbuffer = EGL_NO_SURFACE; GLuint fbo{}, hudTexture{}, cursorProgram{};
    std::vector<XrSwapchainImageOpenGLESKHR> images;
    bool running = false, glInitialized = false; int revision = -1;
    bool picoControllers = false;
    size_t pointerHand = 1; // Keep hover and press on the same hand; right by default.
    Runtime(JNIEnv* e, jobject a) : env(e), activity(a) {
        jclass cls = env->GetObjectClass(activity);
        auto getActivity=env->GetMethodID(cls, "getActivity", "()Landroid/app/Activity;");
        androidActivity=env->CallObjectMethod(activity,getActivity);
        status = env->GetMethodID(cls, "onXrStatus", "(Ljava/lang/String;)V");
        glReady = env->GetMethodID(cls, "onGlReady", "()V");
        glFrame = env->GetMethodID(cls, "onXrFrame", "(ZFF)I");
        bitmapMethod = env->GetMethodID(cls, "getHudBitmap", "()Landroid/graphics/Bitmap;");
        shutdown = env->GetMethodID(cls, "onGlShutdown", "()V");
        env->DeleteLocalRef(cls);
    }
    void log(const std::string& s) {
        auto value = env->NewStringUTF(s.c_str()); env->CallVoidMethod(activity, status, value); env->DeleteLocalRef(value);
    }
    void javaCheck() {
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); throw std::runtime_error("Java GL callback failed; inspect logcat"); }
    }
    template<class T> T function(const char* name) {
        PFN_xrVoidFunction p{}; check(xrGetInstanceProcAddr(instance, name, &p), name); return reinterpret_cast<T>(p);
    }
    XrPath path(const char* p) { XrPath r{}; check(xrStringToPath(instance, p, &r), p); return r; }
    void initialize() {
        JavaVM* vm{}; env->GetJavaVM(&vm);
        PFN_xrVoidFunction initPtr{};
        check(xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", &initPtr), "loader init lookup");
        XrLoaderInitInfoAndroidKHR loader{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
        loader.applicationVM = vm; loader.applicationContext = androidActivity;
        check(reinterpret_cast<PFN_xrInitializeLoaderKHR>(initPtr)(reinterpret_cast<XrLoaderInitInfoBaseHeaderKHR*>(&loader)), "loader init");
        uint32_t count{}; check(xrEnumerateInstanceExtensionProperties(nullptr, 0, &count, nullptr), "extension count");
        std::vector<XrExtensionProperties> properties(count, {XR_TYPE_EXTENSION_PROPERTIES});
        check(xrEnumerateInstanceExtensionProperties(nullptr, count, &count, properties.data()), "extensions");
        for (const auto& p : properties) __android_log_print(ANDROID_LOG_INFO, "SynapathOpenXR", "EXT %s", p.extensionName);
        auto available = [&](const char* name) { for (const auto& p : properties) if (!strcmp(name, p.extensionName)) return true; return false; };
        std::vector<const char*> extensions{XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME};
        if (!available(XR_FB_PASSTHROUGH_EXTENSION_NAME)) throw std::runtime_error("Runtime lacks XR_FB_passthrough; refusing an opaque capture UI");
        extensions.push_back(XR_FB_PASSTHROUGH_EXTENSION_NAME);
        picoControllers = available(XR_BD_CONTROLLER_INTERACTION_EXTENSION_NAME);
        if (picoControllers) extensions.push_back(XR_BD_CONTROLLER_INTERACTION_EXTENSION_NAME);
        XrInstanceCreateInfoAndroidKHR android{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR}; android.applicationVM = vm; android.applicationActivity = androidActivity;
        XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO}; ci.next = &android;
        strcpy(ci.applicationInfo.applicationName, "Synapath Capture HUD"); ci.applicationInfo.applicationVersion = 1;
        ci.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 34);
        ci.enabledExtensionCount = extensions.size(); ci.enabledExtensionNames = extensions.data();
        check(xrCreateInstance(&ci, &instance), "create instance");
        XrInstanceProperties ip{XR_TYPE_INSTANCE_PROPERTIES}; check(xrGetInstanceProperties(instance, &ip), "runtime properties");
        log(std::string("XR_RUNTIME ") + ip.runtimeName);
        XrSystemGetInfo get{XR_TYPE_SYSTEM_GET_INFO}; get.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
        XrSystemId system{}; check(xrGetSystem(instance, &get, &system), "get system");
        XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
        check(function<PFN_xrGetOpenGLESGraphicsRequirementsKHR>("xrGetOpenGLESGraphicsRequirementsKHR")(instance, system, &requirements), "GLES requirements");
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (!eglInitialize(display, nullptr, nullptr)) throw std::runtime_error("eglInitialize");
        EGLint attrs[]{EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,
            EGL_SURFACE_TYPE,EGL_PBUFFER_BIT|EGL_WINDOW_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,0x3142,1,EGL_NONE};
        EGLConfig config{}; EGLint configs{};
        if (!eglChooseConfig(display, attrs, &config, 1, &configs) || configs == 0) throw std::runtime_error("No recordable ES3 EGL config");
        EGLint ctxAttrs[]{EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttrs);
        EGLint surfaceAttrs[]{EGL_WIDTH,WIDTH,EGL_HEIGHT,HEIGHT,EGL_NONE};
        pbuffer = eglCreatePbufferSurface(display, config, surfaceAttrs);
        if (context == EGL_NO_CONTEXT || pbuffer == EGL_NO_SURFACE || !eglMakeCurrent(display, pbuffer, pbuffer, context)) throw std::runtime_error("EGL context/pbuffer setup");
        XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR}; binding.display = display; binding.config = config; binding.context = context;
        XrSessionCreateInfo sc{XR_TYPE_SESSION_CREATE_INFO}; sc.next = &binding; sc.systemId = system;
        check(xrCreateSession(instance, &sc, &session), "create session");
        XrReferenceSpaceCreateInfo space{XR_TYPE_REFERENCE_SPACE_CREATE_INFO}; space.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW; space.poseInReferenceSpace.orientation.w = 1;
        check(xrCreateReferenceSpace(session, &space, &view), "create VIEW space");
        space.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
        check(xrCreateReferenceSpace(session, &space, &local), "create LOCAL space");
        initializeActions();
        auto createPt = function<PFN_xrCreatePassthroughFB>("xrCreatePassthroughFB");
        auto createPtLayer = function<PFN_xrCreatePassthroughLayerFB>("xrCreatePassthroughLayerFB");
        destroyPassthrough = function<PFN_xrDestroyPassthroughFB>("xrDestroyPassthroughFB");
        destroyPassthroughLayer = function<PFN_xrDestroyPassthroughLayerFB>("xrDestroyPassthroughLayerFB");
        XrPassthroughCreateInfoFB pc{XR_TYPE_PASSTHROUGH_CREATE_INFO_FB}; pc.flags = XR_PASSTHROUGH_IS_RUNNING_AT_CREATION_BIT_FB;
        check(createPt(session, &pc, &passthrough), "create passthrough");
        XrPassthroughLayerCreateInfoFB plc{XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB}; plc.passthrough = passthrough;
        plc.flags = XR_PASSTHROUGH_IS_RUNNING_AT_CREATION_BIT_FB; plc.purpose = XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB;
        check(createPtLayer(session, &plc, &passthroughLayer), "create passthrough layer");
        uint32_t formatsCount{}; check(xrEnumerateSwapchainFormats(session, 0, &formatsCount, nullptr), "formats count");
        std::vector<int64_t> formats(formatsCount); check(xrEnumerateSwapchainFormats(session, formatsCount, &formatsCount, formats.data()), "formats");
        int64_t format = 0; for (auto f : formats) if (f == GL_RGBA8) format = f;
        if (!format) throw std::runtime_error("XR swapchain does not support GL_RGBA8");
        XrSwapchainCreateInfo sw{XR_TYPE_SWAPCHAIN_CREATE_INFO}; sw.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
        sw.format = format; sw.sampleCount = 1; sw.width = WIDTH; sw.height = HEIGHT; sw.faceCount = 1; sw.arraySize = 1; sw.mipCount = 1;
        check(xrCreateSwapchain(session, &sw, &swapchain), "create swapchain");
        uint32_t imageCount{}; check(xrEnumerateSwapchainImages(swapchain, 0, &imageCount, nullptr), "image count");
        images.resize(imageCount, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        check(xrEnumerateSwapchainImages(swapchain, imageCount, &imageCount, reinterpret_cast<XrSwapchainImageBaseHeader*>(images.data())), "swapchain images");
        glGenFramebuffers(1, &fbo); glGenTextures(1, &hudTexture);
        glBindTexture(GL_TEXTURE_2D, hudTexture); glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, WIDTH, HEIGHT);
        auto shader = [](GLenum type,const char* source) {
            GLuint id=glCreateShader(type);glShaderSource(id,1,&source,nullptr);glCompileShader(id);
            GLint ok{};glGetShaderiv(id,GL_COMPILE_STATUS,&ok);
            if (!ok) { glDeleteShader(id);throw std::runtime_error("Cursor shader compilation failed"); }
            return id;
        };
        GLuint vs=shader(GL_VERTEX_SHADER,"#version 300 es\nuniform vec2 position;void main(){gl_Position=vec4(position,0,1);gl_PointSize=14.0;}");
        GLuint fs=shader(GL_FRAGMENT_SHADER,"#version 300 es\nprecision mediump float;out vec4 color;void main(){if(distance(gl_PointCoord,vec2(.5))>.5)discard;color=vec4(1);}");
        cursorProgram=glCreateProgram();glAttachShader(cursorProgram,vs);glAttachShader(cursorProgram,fs);glLinkProgram(cursorProgram);
        GLint linked{};glGetProgramiv(cursorProgram,GL_LINK_STATUS,&linked);glDeleteShader(vs);glDeleteShader(fs);
        if (!linked) throw std::runtime_error("Cursor shader linking failed");
        glInitialized = true; env->CallVoidMethod(activity, glReady); javaCheck();
        log("XR_READY view_space_quad=1 passthrough=1 capture=PXRCaptureSDK");
    }
    void initializeActions() {
        hands = {path("/user/hand/left"), path("/user/hand/right")};
        XrActionSetCreateInfo ac{XR_TYPE_ACTION_SET_CREATE_INFO}; strcpy(ac.actionSetName, "capture"); strcpy(ac.localizedActionSetName, "Capture");
        check(xrCreateActionSet(instance, &ac, &actions), "action set");
        auto action = [&](const char* name, XrActionType type, XrAction* output) {
            XrActionCreateInfo ai{XR_TYPE_ACTION_CREATE_INFO}; strcpy(ai.actionName, name); strcpy(ai.localizedActionName, name);
            ai.actionType = type; ai.countSubactionPaths = hands.size(); ai.subactionPaths = hands.data();
            check(xrCreateAction(actions, &ai, output), "create action");
        };
        action("select", XR_ACTION_TYPE_BOOLEAN_INPUT, &select); action("trigger", XR_ACTION_TYPE_FLOAT_INPUT, &trigger); action("aim", XR_ACTION_TYPE_POSE_INPUT, &aim);
        auto suggest = [&](const char* profile, const char* button, XrAction buttonAction) {
            std::vector<XrActionSuggestedBinding> bindings;
            for (const char* hand : {"/user/hand/left", "/user/hand/right"}) {
                std::string h(hand); bindings.push_back({buttonAction, path((h + button).c_str())});
                bindings.push_back({aim, path((h + "/input/aim/pose").c_str())});
            }
            XrInteractionProfileSuggestedBinding info{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING}; info.interactionProfile = path(profile); info.countSuggestedBindings = bindings.size(); info.suggestedBindings = bindings.data();
            auto r = xrSuggestInteractionProfileBindings(instance, &info);
            __android_log_print(ANDROID_LOG_INFO, "SynapathOpenXR", "BIND profile=%s result=%d", profile, r);
        };
        suggest("/interaction_profiles/khr/simple_controller", "/input/select/click", select);
        if (picoControllers) {
            suggest("/interaction_profiles/bytedance/pico4_controller", "/input/trigger/value", trigger);
            suggest("/interaction_profiles/bytedance/pico4s_controller", "/input/trigger/value", trigger);
        }
        for (size_t i=0;i<hands.size();i++) { XrActionSpaceCreateInfo ai{XR_TYPE_ACTION_SPACE_CREATE_INFO}; ai.action = aim; ai.subactionPath = hands[i]; ai.poseInActionSpace.orientation.w = 1; check(xrCreateActionSpace(session, &ai, &aimSpaces[i]), "aim space"); }
        XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO}; attach.countActionSets = 1; attach.actionSets = &actions;
        check(xrAttachSessionActionSets(session, &attach), "attach actions");
    }
    void input(XrTime time, bool& down, float& cx, float& cy) {
        XrActiveActionSet active{actions, XR_NULL_PATH}; XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO}; sync.countActiveActionSets = 1; sync.activeActionSets = &active;
        if (xrSyncActions(session, &sync) != XR_SUCCESS) return;
        XrSpaceLocation head{XR_TYPE_SPACE_LOCATION};
        constexpr XrSpaceLocationFlags valid = XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
        if (XR_FAILED(xrLocateSpace(view, local, time, &head)) || (head.locationFlags & valid) != valid) return;
        auto rotate = [](XrQuaternionf q, XrVector3f v) {
            XrVector3f t{2*(q.y*v.z-q.z*v.y),2*(q.z*v.x-q.x*v.z),2*(q.x*v.y-q.y*v.x)};
            return XrVector3f{v.x+q.w*t.x+(q.y*t.z-q.z*t.y),v.y+q.w*t.y+(q.z*t.x-q.x*t.z),v.z+q.w*t.z+(q.x*t.y-q.y*t.x)};
        };
        XrQuaternionf inverseHead{-head.pose.orientation.x,-head.pose.orientation.y,-head.pose.orientation.z,head.pose.orientation.w};
        static int diagnostics = 0;
        for (size_t i=0;i<hands.size();i++) {
            XrActionStateGetInfo get{XR_TYPE_ACTION_STATE_GET_INFO}; get.subactionPath = hands[i];
            get.action = select; XrActionStateBoolean s{XR_TYPE_ACTION_STATE_BOOLEAN}; xrGetActionStateBoolean(session, &get, &s);
            get.action = trigger; XrActionStateFloat t{XR_TYPE_ACTION_STATE_FLOAT}; xrGetActionStateFloat(session, &get, &t);
            bool pressed = (s.isActive && s.currentState) || (t.isActive && t.currentState > .7f);
            if (pressed && pointerHand != i) { pointerHand = i; cx=-1;cy=-1;down=false; }
            if (i != pointerHand) continue;
            get.action = aim; XrActionStatePose poseState{XR_TYPE_ACTION_STATE_POSE};
            auto poseResult = xrGetActionStatePose(session, &get, &poseState);
            if (XR_FAILED(poseResult) || !poseState.isActive) continue;
            XrSpaceLocation location{XR_TYPE_SPACE_LOCATION};
            if (XR_FAILED(xrLocateSpace(aimSpaces[i], local, time, &location))) continue;
            if ((location.locationFlags & valid) != valid) continue;
            auto q = location.pose.orientation; auto p = location.pose.position;
            // Query action poses in LOCAL (as in normal XR scene rendering), then
            // transform both ray origin and direction into the head-follow plane.
            auto origin = rotate(inverseHead, {p.x-head.pose.position.x,p.y-head.pose.position.y,p.z-head.pose.position.z});
            auto direction = rotate(inverseHead, rotate(q,{0,0,-1}));
            p = origin;
            float dx=direction.x,dy=direction.y,dz=direction.z;
            if (++diagnostics % 300 == 0) __android_log_print(ANDROID_LOG_INFO,"SynapathOpenXR","XR_AIM hand=%zu active=%u flags=%llu p=%.3f,%.3f,%.3f q=%.3f,%.3f,%.3f,%.3f dir=%.3f,%.3f,%.3f",i,poseState.isActive,(unsigned long long)location.locationFlags,p.x,p.y,p.z,q.x,q.y,q.z,q.w,dx,dy,dz);
            if (dz >= -.001f) continue;
            float distance = (QUAD_Z-p.z)/dz; if (distance <= 0) continue;
            float x = (p.x+distance*dx)/QUAD_WIDTH + .5f, y = .5f-(p.y+distance*dy)/QUAD_HEIGHT;
            if (x >= 0 && x <= 1 && y >= 0 && y <= 1 && (cx < 0 || pressed)) { cx = x*WIDTH; cy = y*HEIGHT; down = pressed; }
        }
    }
    void uploadHud(int nextRevision) {
        if (revision == nextRevision) return;
        env->MonitorEnter(activity);
        jobject bitmap = env->CallObjectMethod(activity, bitmapMethod); javaCheck();
        AndroidBitmapInfo info{}; void* pixels{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.stride != WIDTH*4) { env->DeleteLocalRef(bitmap); env->MonitorExit(activity); throw std::runtime_error("Unexpected HUD bitmap format"); }
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) { env->DeleteLocalRef(bitmap); env->MonitorExit(activity); throw std::runtime_error("HUD bitmap lock"); }
        glBindTexture(GL_TEXTURE_2D, hudTexture); glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        AndroidBitmap_unlockPixels(env, bitmap); env->DeleteLocalRef(bitmap); env->MonitorExit(activity); revision = nextRevision;
    }
    void loop() {
        int64_t frameCount=0;
        auto started=std::chrono::steady_clock::now();
        while (!stopRequested) {
            XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
            while (xrPollEvent(instance, &event) == XR_SUCCESS) {
                if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                    auto* changed = reinterpret_cast<XrEventDataSessionStateChanged*>(&event);
                    log("XR_SESSION_STATE " + std::to_string(changed->state));
                    if (changed->state == XR_SESSION_STATE_READY) {
                        XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO}; begin.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                        check(xrBeginSession(session, &begin), "begin session"); running = true;
                    } else if (changed->state == XR_SESSION_STATE_STOPPING) { running = false; check(xrEndSession(session), "end session"); }
                    else if (changed->state == XR_SESSION_STATE_EXITING || changed->state == XR_SESSION_STATE_LOSS_PENDING) stopRequested = true;
                } else if (event.type == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) stopRequested = true;
                event = {XR_TYPE_EVENT_DATA_BUFFER};
            }
            if (stopRequested) break;
            if (!running) { std::this_thread::sleep_for(std::chrono::milliseconds(10)); continue; }
            XrFrameWaitInfo wait{XR_TYPE_FRAME_WAIT_INFO}; XrFrameState state{XR_TYPE_FRAME_STATE}; check(xrWaitFrame(session, &wait, &state), "wait frame");
            if (++frameCount%600==0) {
                double seconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-started).count();
                __android_log_print(ANDROID_LOG_INFO,"SynapathOpenXR","XR_CADENCE frames=%lld actual_hz=%.2f predicted_period_ns=%lld",(long long)frameCount,frameCount/seconds,(long long)state.predictedDisplayPeriod);
            }
            XrFrameBeginInfo begin{XR_TYPE_FRAME_BEGIN_INFO}; check(xrBeginFrame(session, &begin), "begin frame");
            bool down = false; float x = -1, y = -1; input(state.predictedDisplayTime, down, x, y);
            int rev = env->CallIntMethod(activity, glFrame, static_cast<jboolean>(down), x, y); javaCheck();
            std::vector<const XrCompositionLayerBaseHeader*> layers;
            XrCompositionLayerPassthroughFB pt{XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB}; pt.layerHandle = passthroughLayer;
            XrCompositionLayerQuad quad{XR_TYPE_COMPOSITION_LAYER_QUAD};
            if (state.shouldRender) {
                uploadHud(rev);
                uint32_t index{}; XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO}; check(xrAcquireSwapchainImage(swapchain, &acquire, &index), "acquire image");
                XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO}; wi.timeout = XR_INFINITE_DURATION; check(xrWaitSwapchainImage(swapchain, &wi), "wait image");
                // Blit with a vertical flip: Android bitmaps are top-down, GL textures bottom-up.
                GLuint readFbo{}; glGenFramebuffers(1, &readFbo);
                glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo); glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, hudTexture, 0);
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo); glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, images[index].image, 0);
                if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw std::runtime_error("XR framebuffer incomplete");
                glBlitFramebuffer(0,HEIGHT,WIDTH,0,0,0,WIDTH,HEIGHT,GL_COLOR_BUFFER_BIT,GL_NEAREST);
                if (x>=0 && y>=0) {
                    glViewport(0,0,WIDTH,HEIGHT);glDisable(GL_BLEND);glUseProgram(cursorProgram);
                    glUniform2f(glGetUniformLocation(cursorProgram,"position"),2*x/WIDTH-1,1-2*y/HEIGHT);
                    glDrawArrays(GL_POINTS,0,1);
                }
                glBindFramebuffer(GL_FRAMEBUFFER, 0); glDeleteFramebuffers(1, &readFbo); glFlush();
                XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO}; check(xrReleaseSwapchainImage(swapchain, &release), "release image");
                quad.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
                quad.space = view; quad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
                quad.subImage.swapchain = swapchain; quad.subImage.imageRect.extent = {WIDTH,HEIGHT};
                quad.pose.orientation.w = 1; quad.pose.position.z = QUAD_Z; quad.size = {QUAD_WIDTH,QUAD_HEIGHT};
                layers.push_back(reinterpret_cast<const XrCompositionLayerBaseHeader*>(&pt));
                layers.push_back(reinterpret_cast<const XrCompositionLayerBaseHeader*>(&quad));
            }
            XrFrameEndInfo end{XR_TYPE_FRAME_END_INFO}; end.displayTime = state.predictedDisplayTime;
            end.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
            end.layerCount = layers.size(); end.layers = layers.data(); check(xrEndFrame(session, &end), "end frame");
        }
    }
    ~Runtime() {
        if (glInitialized) { env->CallVoidMethod(activity, shutdown); if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); } }
        if (context != EGL_NO_CONTEXT) { glDeleteFramebuffers(1, &fbo); glDeleteTextures(1, &hudTexture); glDeleteProgram(cursorProgram); }
        if (passthroughLayer && destroyPassthroughLayer) destroyPassthroughLayer(passthroughLayer);
        if (passthrough && destroyPassthrough) destroyPassthrough(passthrough);
        if (swapchain) xrDestroySwapchain(swapchain);
        for (auto space : aimSpaces) if (space) xrDestroySpace(space);
        if (view) xrDestroySpace(view);
        if (local) xrDestroySpace(local);
        if (session) xrDestroySession(session);
        if (actions) xrDestroyActionSet(actions);
        if (instance) xrDestroyInstance(instance);
        if (display != EGL_NO_DISPLAY) {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (pbuffer != EGL_NO_SURFACE) eglDestroySurface(display, pbuffer);
            if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
            eglTerminate(display);
        }
        if (androidActivity) env->DeleteLocalRef(androidActivity);
    }
};
}
extern "C" JNIEXPORT void JNICALL Java_com_picoxr_capturesdkdemo_xr_OpenXrUiHost_nativeRun(JNIEnv* env, jobject activity) {
    Runtime runtime(env, activity);
    try { runtime.initialize(); runtime.loop(); }
    catch (const std::exception& e) { runtime.log(std::string("XR_ERROR ") + e.what()); }
}
extern "C" JNIEXPORT void JNICALL Java_com_picoxr_capturesdkdemo_xr_OpenXrUiHost_nativeStop(JNIEnv*, jobject) { stopRequested = true; }
extern "C" JNIEXPORT void JNICALL Java_com_picoxr_capturesdkdemo_xr_OpenXrUiHost_nativePrepare(JNIEnv*, jobject) { stopRequested = false; }
