package io.github.rufid.core

data class WritePlan(
    val imageName: String,
    val imageSize: Long,
    val targetName: String,
    val targetSize: Long,
) {
    fun validate() {
        require(imageSize > 0) { "Image is empty." }
        require(targetSize > 0) { "Target device has unknown size." }
        require(imageSize <= targetSize) {
            "Image is larger than target device: image=$imageSize target=$targetSize"
        }
    }
}

