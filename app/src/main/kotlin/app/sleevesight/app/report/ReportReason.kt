package app.sleevesight.app.report

/** Why an insight is reported (#390), as the report names it. */
enum class ReportReason(
    val label: String,
) {
    OFFENSIVE("Offensive or inappropriate"),
    WRONG("Wrong"),
    OTHER("Other"),
}
