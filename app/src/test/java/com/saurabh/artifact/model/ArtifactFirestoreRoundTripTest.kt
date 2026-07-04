package com.saurabh.artifact.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Constructor
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor

class ArtifactFirestoreRoundTripTest {

    @Test
    fun `verify isDraftField annotations`() {
        val klass = Artifact::class
        val prop = klass.members.find { it.name == "isDraftField" } as? kotlin.reflect.KProperty<*>
        
        // 1. Verify PropertyName annotation on getter
        val getterAnnotation = prop?.getter?.annotations?.find { it is PropertyName } as? PropertyName
        assertEquals("isDraft", getterAnnotation?.value)

        // 2. Verify PropertyName annotation on field
        val field = Artifact::class.java.getDeclaredField("isDraftField")
        val fieldAnnotation = field.getAnnotation(PropertyName::class.java)
        assertEquals("isDraft", fieldAnnotation?.value)
    }

    @Test
    fun `verify isDraft computed property is excluded`() {
        val klass = Artifact::class
        val prop = klass.members.find { it.name == "isDraft" } as? kotlin.reflect.KProperty<*>
        
        val hasExclude = prop?.getter?.annotations?.any { 
            it.annotationClass.simpleName == "Exclude" 
        } ?: false
        
        assertTrue("isDraft computed property should be excluded from getter", hasExclude)
    }

    @Test
    fun `verify Artifact constructor has expected parameters`() {
        // This test ensures the constructor remains stable for Firestore reflection
        val constructor = Artifact::class.primaryConstructor
        val params = constructor?.parameters
        
        val isDraftFieldParam = params?.find { it.name == "isDraftField" }
        assertTrue("Constructor should have isDraftField parameter", isDraftFieldParam != null)
    }
}
