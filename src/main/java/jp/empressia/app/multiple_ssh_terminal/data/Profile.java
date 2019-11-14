package jp.empressia.app.multiple_ssh_terminal.data;

/**
 * プロファイル。
 * @author すふぃあ
 */
public class Profile {

	/** 接続情報。 */
	public NamedTerminalInformation[] Terminals;
	/** 保持する行数（初期値-1で無制限）。 */
	public int LimitOfLines = -1;
	/** 接続後に流すコマンド一式。 */
	public Command[] Commands;

}
