FROM golang:1.27.4 AS go
FROM eclipse-temurin:17-jdk-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip git ca-certificates build-essential && rm -rf /var/lib/apt/lists/*
COPY --from=go /usr/local/go /usr/local/go
ENV PATH=/usr/local/go/bin:/opt/android-sdk/cmdline-tools/latest/bin:$PATH
ENV ANDROID_SDK_ROOT=/opt/android-sdk ANDROID_HOME=/opt/android-sdk ANDROID_NDK_HOME=/opt/android-sdk/ndk/27.2.12479018
RUN curl -fL --retry 3 https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip -o /tmp/android-tools.zip \
 && echo '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  /tmp/android-tools.zip' | sha256sum -c - \
 && mkdir -p /opt/android-sdk/cmdline-tools \
 && unzip -q /tmp/android-tools.zip -d /opt/android-sdk/cmdline-tools \
 && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
 && rm /tmp/android-tools.zip
RUN yes | sdkmanager --licenses >/dev/null
RUN sdkmanager 'platforms;android-35' 'build-tools;36.0.0' 'platform-tools' 'ndk;27.2.12479018'
WORKDIR /workspace
