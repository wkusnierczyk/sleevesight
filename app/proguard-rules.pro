# R8 rules for the release build, on top of `proguard-android-optimize.txt` and the rules the
# libraries ship in their jars: kotlinx.serialization keeps the generated serializers and the
# `Companion` and `INSTANCE` lookups, kotlinx.coroutines and Ktor keep their atomically updated
# fields, and Ktor keeps its engines for `ServiceLoader`. Add a rule here only for what those miss,
# with the reason, and check it with the release build (`design/release.md`, *R8*).
