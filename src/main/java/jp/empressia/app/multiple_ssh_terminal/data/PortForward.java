package jp.empressia.app.multiple_ssh_terminal.data;

/**
 * ポートフォワード情報。
 * @author すふぃあ
 */
public class PortForward extends TerminalInformation {

	/** ローカルにバインドするポート番号。初期値はJSchでの自動バインド用の値。 */
	public int LocalPort = 0;

}
