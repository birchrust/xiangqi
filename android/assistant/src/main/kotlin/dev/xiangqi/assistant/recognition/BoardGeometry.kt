package dev.xiangqi.assistant.recognition

/** A9, I9, A0, I0 corners in normalized display coordinates. */
data class BoardGeometry(val corners: List<BoardPoint>) {
    init { require(corners.size == 4) }

    fun point(square: Int): BoardPoint {
        require(square in 0..89)
        val (a, b, c, d) = corners
        val dx1 = b.x - d.x; val dx2 = c.x - d.x
        val dy1 = b.y - d.y; val dy2 = c.y - d.y
        val dx3 = a.x - b.x - c.x + d.x
        val dy3 = a.y - b.y - c.y + d.y
        val determinant = dx1 * dy2 - dx2 * dy1
        require(kotlin.math.abs(determinant) > 0.000001f) { "棋盘定位不稳定" }
        val g = (dx3 * dy2 - dx2 * dy3) / determinant
        val h = (dx1 * dy3 - dx3 * dy1) / determinant
        val u = (square % 9) / 8f; val v = (square / 9) / 9f
        val divisor = 1 + g * u + h * v
        return BoardPoint(
            ((b.x - a.x + g * b.x) * u + (c.x - a.x + h * c.x) * v + a.x) / divisor,
            ((b.y - a.y + g * b.y) * u + (c.y - a.y + h * c.y) * v + a.y) / divisor)
    }
}
