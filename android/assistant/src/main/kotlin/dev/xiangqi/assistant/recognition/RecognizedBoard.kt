package dev.xiangqi.assistant.recognition

import dev.xiangqi.assistant.domain.board.BoardPosition

data class RecognizedBoard(val position: BoardPosition, val geometry: BoardGeometry)
