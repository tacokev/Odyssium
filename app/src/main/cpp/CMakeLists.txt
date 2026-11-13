# Only built when the Discord Social SDK is present (see app/build.gradle.kts).
cmake_minimum_required(VERSION 3.22.1)
project(odyssium_discord LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Provided by the SDK's AAR through Prefab.
find_package(discord_partner_sdk REQUIRED CONFIG)

add_library(odyssium_discord SHARED discord_presence.cpp)
target_link_libraries(odyssium_discord discord_partner_sdk::discord_partner_sdk log)
