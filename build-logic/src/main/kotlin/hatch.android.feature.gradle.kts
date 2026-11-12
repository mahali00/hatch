// Features build on :core and :design and never on each other (enforced in the root build).
plugins {
    id("hatch.android.library")
    id("hatch.android.compose")
}

dependencies {
    "implementation"(project(":core"))
    "implementation"(project(":design"))
}
