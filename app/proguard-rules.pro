# Release builds run R8 (isMinifyEnabled = true).
#
# No app-specific keep rules are needed:
#  - MapLibre, AndroidX, WorkManager, DataStore, coroutines and kotlinx.serialization
#    ship their own consumer rules (generated serializers are kept by them).
#  - Services, the Activity, the Application and the Worker are kept via the manifest
#    or WorkManager's own rules.
#
# If a release build crashes where debug doesn't, check the R8 mapping at
# app/build/outputs/mapping/release/mapping.txt and add a -keep here.
