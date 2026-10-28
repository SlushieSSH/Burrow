package com.bigslushie.burrow

private const val lowestTemperature = 0.3f
private const val temperatureRange = 1.0f
private const val shortMessageWords = 6

fun fillPlaceholders(text: String, character: Character, profile: Profile): String = text
    .replace("{{char}}", character.name, ignoreCase = true)
    .replace("{{user}}", profile.name.ifBlank { "the user" }, ignoreCase = true)

fun systemPrompt(character: Character, profile: Profile): String = buildString {
    appendLine("You are ${character.name}. Stay in character at all times, write only as ${character.name} and never speak or act for the user.")
    appendLine("Write actions and narration between *asterisks* and speech as plain text.")
    appendLine("Keep the scene physically real. Track what ${character.name} is holding, eating, wearing, where they are and how their body is placed, and keep it consistent between replies.")
    appendLine("Only do what a real body could do in that moment: with a full mouth, chew and swallow before talking or speak muffled, with full hands, put something down before grabbing something else, and nobody hears or sees what is out of range.")
    appendLine(character.replyLength.instruction)
    appendLine("That length is a limit, not a goal. Match the user's message: a greeting or a few words gets one or two sentences back, like a real person would answer.")
    appendLine()
    appendLine("Personality:")
    appendLine(fillPlaceholders(character.personality, character, profile))
    if (character.scenario.isNotBlank()) {
        appendLine()
        appendLine("Scenario:")
        appendLine(fillPlaceholders(character.scenario, character, profile))
    }
    if (profile.name.isNotBlank() || profile.description.isNotBlank()) {
        appendLine()
        appendLine("The user:")
        if (profile.name.isNotBlank()) appendLine("Name: ${profile.name}")
        if (profile.description.isNotBlank()) appendLine(fillPlaceholders(profile.description, character, profile))
    }
    if (character.exampleDialogue.isNotBlank()) {
        appendLine()
        appendLine("Example of how ${character.name} talks:")
        appendLine(fillPlaceholders(character.exampleDialogue, character, profile))
    }
    if (character.systemPrompt.isNotBlank()) {
        appendLine()
        appendLine(fillPlaceholders(character.systemPrompt, character, profile))
    }
}.trim()

fun withLengthReminder(message: String, length: ReplyLength): String {
    val reminder = if (message.trim().split(Regex("\\s+")).size <= shortMessageWords) "(Reply to this in one or two short sentences.)" else length.reminder
    return "$message\n\n$reminder"
}

fun temperatureFor(character: Character): Float = lowestTemperature + character.creativity * temperatureRange
