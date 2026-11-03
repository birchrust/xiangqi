package dev.xiangqi.assistant.domain.tracking

import dev.xiangqi.assistant.domain.board.BoardPosition

data class TrackedBoard(val position: BoardPosition, val observation: Observation)
