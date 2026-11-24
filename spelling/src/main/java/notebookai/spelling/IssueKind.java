package notebookai.spelling;

/** Why a token was flagged. Both kinds mean only "not in the dictionary": unknown does not mean incorrect. */
public enum IssueKind {
    /** A lower-case, UPPER-CASE (4+ letters) or hyphen-part token that is not in the dictionary. */
    UNKNOWN_WORD,
    /** A Title-case token that is not in the dictionary; often a name or a place the list does not know. */
    UNKNOWN_CAPITALIZED
}
