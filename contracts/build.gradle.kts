plugins {
    java
}

dependencies {
    // Plain Java records — intentionally dependency-free so every service
    // shares the same event contract without dragging in a Spring stack.
    // Jackson serialization happens in the producing/consuming services.
}