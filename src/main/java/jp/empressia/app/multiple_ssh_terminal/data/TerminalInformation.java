package jp.empressia.app.multiple_ssh_terminal.data;

/**
 * 接続先情報。
 * @author すふぃあ
 */
public class TerminalInformation {

	/** 接続先ホスト。 */
	public String Host;
	/** 接続先ポート。 */
	public int Port = 22;
	/** 接続先ユーザーID。 */
	public String UserID;
	/** 接続先パスワード。 */
	public String Password;

	/** ポートフォワード情報。 */
	public PortForward PortForward;

}
