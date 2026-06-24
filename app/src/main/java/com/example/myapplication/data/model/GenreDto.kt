package com.example.myapplication.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.example.myapplication.domain.model.Genre

@Serializable
data class GenreDto(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String
) {
    fun toDomain(): Genre {
        return Genre(id = id, name = name)
    }
}
