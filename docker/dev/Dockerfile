FROM eclipse-temurin:17-jdk-jammy
ARG ANDROID_CMDLINE_TOOLS_VERSION=11076708
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip make ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/android-sdk/cmdline-tools \
    && curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" -o /tmp/tools.zip \
    && unzip -q /tmp/tools.zip -d /tmp/android-tools \
    && mv /tmp/android-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && (yes || true) | sdkmanager --licenses >/dev/null \
    && sdkmanager 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0' \
    && rm -rf /tmp/tools.zip /tmp/android-tools
WORKDIR /workspace/synapath-pico
