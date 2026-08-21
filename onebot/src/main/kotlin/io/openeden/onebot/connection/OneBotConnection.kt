package io.openeden.onebot.connection

import kotlinx.coroutines.sync.Mutex

class OneBotConnection internal constructor(
    val selfId: String,
    val epoch: Long,
    val socket: OneBotSocket,
    internal val sendMutex: Mutex = Mutex(),
)
