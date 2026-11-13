// Bridge from DiscordPresence.kt to the Discord Social SDK's Rich Presence.
//
// Only Rich Presence is used, which the SDK sets through the Discord app installed on the phone
// (no Discord login, no Client::Connect). Every function here is called from DiscordPresence's
// single worker thread, so the client needs no locking.

#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"

#include <android/log.h>
#include <jni.h>

#include <memory>
#include <optional>
#include <string>

namespace {

constexpr const char* kTag = "OdyssiumDiscord";

std::unique_ptr<discordpp::Client> client;

std::optional<std::string> toOptional(JNIEnv* env, jstring value) {
    if (value == nullptr) return std::nullopt;
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    if (result.empty()) return std::nullopt;
    return result;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_skintclub_odyssey_playback_DiscordPresence_nativeInit(JNIEnv*, jclass, jlong applicationId) {
    if (client) return;
    client = std::make_unique<discordpp::Client>();
    client->SetApplicationId(static_cast<uint64_t>(applicationId));
}

JNIEXPORT void JNICALL
Java_com_skintclub_odyssey_playback_DiscordPresence_nativeUpdate(
    JNIEnv* env,
    jclass,
    jstring details,
    jstring state,
    jstring largeImage,
    jstring largeText,
    jlong startSeconds,
    jlong endSeconds) {
    if (!client) return;

    discordpp::Activity activity;
    // Without it Discord labels the activity "Game": the name only defaults to the app's name
    // when the SDK knows it from a Discord login, which Rich Presence through the Discord app
    // doesn't have.
    activity.SetName("Odyssium");
    activity.SetType(discordpp::ActivityTypes::Listening);
    // The member list then reads "Listening to <episode>" rather than the app's name.
    activity.SetStatusDisplayType(discordpp::StatusDisplayTypes::Details);
    activity.SetDetails(toOptional(env, details));
    activity.SetState(toOptional(env, state));

    auto image = toOptional(env, largeImage);
    if (image) {
        discordpp::ActivityAssets assets;
        assets.SetLargeImage(image);
        assets.SetLargeText(toOptional(env, largeText));
        activity.SetAssets(assets);
    }

    // Start (and end, when the length is known) make Discord show a progress bar; a paused
    // episode has neither.
    if (startSeconds > 0) {
        discordpp::ActivityTimestamps timestamps;
        timestamps.SetStart(static_cast<uint64_t>(startSeconds));
        if (endSeconds > startSeconds) timestamps.SetEnd(static_cast<uint64_t>(endSeconds));
        activity.SetTimestamps(timestamps);
    }

    client->UpdateRichPresence(std::move(activity), [](const discordpp::ClientResult& result) {
        if (!result.Successful()) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "Rich Presence update failed: %s", result.Error().c_str());
        }
    });
}

JNIEXPORT void JNICALL
Java_com_skintclub_odyssey_playback_DiscordPresence_nativeClear(JNIEnv*, jclass) {
    if (client) client->ClearRichPresence();
}

JNIEXPORT void JNICALL
Java_com_skintclub_odyssey_playback_DiscordPresence_nativeRunCallbacks(JNIEnv*, jclass) {
    discordpp::RunCallbacks();
}

JNIEXPORT void JNICALL
Java_com_skintclub_odyssey_playback_DiscordPresence_nativeRelease(JNIEnv*, jclass) {
    if (!client) return;
    client->ClearRichPresence();
    discordpp::RunCallbacks();
    client.reset();
}

}  // extern "C"
