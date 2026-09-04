package com.danemadsen.atlas.beerouter.router

internal fun VoiceHint.testCommandString(): String =
    when (command) {
        VoiceHint.Command.TLU -> "TLU"
        VoiceHint.Command.TU -> "TU"
        VoiceHint.Command.TSHL -> "TSHL"
        VoiceHint.Command.TL -> "TL"
        VoiceHint.Command.TSLL -> "TSLL"
        VoiceHint.Command.KL -> "KL"
        VoiceHint.Command.C -> "C"
        VoiceHint.Command.KR -> "KR"
        VoiceHint.Command.TSLR -> "TSLR"
        VoiceHint.Command.TR -> "TR"
        VoiceHint.Command.TSHR -> "TSHR"
        VoiceHint.Command.TRU -> "TRU"
        VoiceHint.Command.RNDB -> "RNDB$exitNumber"
        VoiceHint.Command.RNLB -> "RNLB${-exitNumber}"
        VoiceHint.Command.BL -> "BL"
        VoiceHint.Command.EL -> "EL"
        VoiceHint.Command.ER -> "ER"
        VoiceHint.Command.OFFR -> "OFFR"
        else -> error("unknown command: $command")
    }
