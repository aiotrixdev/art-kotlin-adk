package com.example.artlibrary.agentic

/** Public execution targets are slugs, optionally pinned to a named release. */
fun validateExecutionReference(reference: String): String {
    if (!Regex("^[a-z0-9]+(?:-[a-z0-9]+)*(?:@[a-z0-9]{1,64})?$").matches(reference)) {
        throw IllegalArgumentException("Expected a lowercase slug or slug@buildname")
    }
    return reference
}