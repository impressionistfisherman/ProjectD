package kr.dalmaru.account;

/** COMMIT acknowledgement or rollback could not establish the selected inventory owner. */
public final class SelectionCommitUncertainException extends java.sql.SQLException {
    public SelectionCommitUncertainException(Exception cause) { super("Character selection requires reconnect recovery.","08007",cause); }
}
