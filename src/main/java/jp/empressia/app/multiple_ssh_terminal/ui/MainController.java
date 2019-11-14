package jp.empressia.app.multiple_ssh_terminal.ui;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import jp.empressia.app.multiple_ssh_terminal.data.Command;
import jp.empressia.app.multiple_ssh_terminal.data.NamedTerminalInformation;
import jp.empressia.app.multiple_ssh_terminal.data.PortForward;
import jp.empressia.app.multiple_ssh_terminal.data.Profile;
import jp.empressia.app.multiple_ssh_terminal.io.VT100InputStream;
import jp.empressia.app.multiple_ssh_terminal.plugin.IPlugin;

/**
 * JavaFX の MainController。
 * @author すふぃあ
 */
public class MainController implements AutoCloseable {

	@FXML
	private TabPane PluginTabPane;
	@FXML
	private TabPane ConsoleTabPane;
	@FXML
	private ToggleButton SendToAllToggleButton;
	@FXML
	private TextField InputTextField;
	@FXML
	private ToggleButton CRToggleButton;
	@FXML
	private ToggleButton LFToggleButton;
	@FXML
	private Label StatusLine;

	/** バインディングを初期化します。 */
	private void initializeBindings() {
	}

	/** 初期化します。 */
	@FXML
	public void initialize() {
		this.initializeBindings();
		try {
			this.loadPlugins();
		} catch(IOException ex) {
			this.StatusLine.setText(ex.getMessage());
			ex.printStackTrace();
		}
	}

	private ArrayList<IPlugin> Plugins = new ArrayList<IPlugin>();

	public void loadPlugins() throws IOException {
		ServiceLoader<IPlugin> serviceLoader = ServiceLoader.load(IPlugin.class);
		for(IPlugin plugin : serviceLoader) {
			this.Plugins.add(plugin);
			String name = null;
			try {
				name = plugin.getTabName();
				Node node = plugin.createTabContent();
				if(node == null) {
					throw new IllegalStateException();
				}
				Tab tab = new Tab();
				tab.setText(name);
				tab.setContent(node);
				this.PluginTabPane.getTabs().add(tab);
			} catch(Exception ex) {
				String message = MessageFormat.format("プラグイン[{0}]の初期化に失敗しました。", name);
				this.showMessage("プラグインエラー。", message, this.StatusLine);
			}
		}
	}

	private ReentrantLock Lock = new ReentrantLock();
	private HashMap<Tab, SSHContainer> SSHContainers = new HashMap<Tab, SSHContainer>();
	private HashMap<Tab, TextArea> TextAreas = new HashMap<Tab, TextArea>();
	private class SSHContainer {
		private Session Session;
		private Channel Channel;
		private VT100InputStream Filter;
		private BufferedReader Reader;
		private BufferedWriter Writer;
		@SuppressWarnings("unused")
		private CompletableFuture<?> Task;
		private ArrayList<Session> BridgeSessions;
	}

	@FXML
	public void Action_OpenProfileButton() {
		FileChooser fileChooser = new FileChooser();
		fileChooser.setTitle("プロファイルを開きます。");
		fileChooser.getExtensionFilters().addAll(
			new FileChooser.ExtensionFilter("プロファイルファイル", "*.profile"),
			new FileChooser.ExtensionFilter("すべてのファイル", "*.*")
		);
		fileChooser.setInitialDirectory(new File("./"));
		File targetFile = fileChooser.showOpenDialog(this.StatusLine.getScene().getWindow());
		if(targetFile == null) { return; }
		this.readProfile(targetFile);
	}

	/** プロファイルを読んで、初期化します。 */
	private void readProfile(File targetFile) {
		Profile profile;
		ObjectMapper mapper = new ObjectMapper();
		try {
			profile = mapper.readValue(targetFile, Profile.class);
		} catch(IOException ex) {
			this.showMessage("プロファイル読み込みエラー", ex.getMessage(), this.StatusLine);
			this.StatusLine.setText(ex.getMessage());
			return;
		}

		class Holder {
			SSHContainer SSHContainer;
			TextArea TextArea;
			Tab Tab;
		}
		HashMap<NamedTerminalInformation, Holder> map = new HashMap<NamedTerminalInformation, Holder>();
		for(NamedTerminalInformation info : profile.Terminals) {
			TextArea textArea = new TextArea();
			textArea.setWrapText(true);
			Tab tab = new Tab();
			String name = (info.Name != null) ? info.Name : info.Host;
			tab.setText(name);
			tab.setContent(textArea);
			this.StatusLine.setText(MessageFormat.format("{0}（{1}）へ接続します。", name, info.Host));
			this.Lock.lock();
			SSHContainer container = new SSHContainer();
			try {
				this.SSHContainers.put(tab, container);
				this.TextAreas.put(tab, textArea);
				this.ConsoleTabPane.getTabs().add(tab);
			} finally {
				this.Lock.unlock();
			}
			Holder holder = new Holder();
			holder.SSHContainer = container;
			holder.TextArea = textArea;
			holder.Tab = tab;
			map.put(info, holder);
		}
		ExecutorService connectExecutor = Executors.newSingleThreadExecutor();
		CompletableFuture.runAsync(() -> {
			JSch ssh = new JSch();
			for(NamedTerminalInformation info : profile.Terminals) {
				Holder holder = map.get(info);
				SSHContainer container = holder.SSHContainer;
				TextArea textArea = holder.TextArea;
				Tab tab = holder.Tab;
				String name = tab.getText();
				try {
					Session candidateSession = ssh.getSession(info.UserID, info.Host, info.Port);
					candidateSession.setConfig("StrictHostKeyChecking", "no");
					candidateSession.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
					candidateSession.setPassword(info.Password);
					candidateSession.connect(10 * 1000);
					PortForward forwardInfo = info.PortForward;
					while(forwardInfo != null) {
						int port = candidateSession.setPortForwardingL(forwardInfo.LocalPort, forwardInfo.Host, forwardInfo.Port);
						if(container.BridgeSessions == null) { container.BridgeSessions = new ArrayList<Session>(); }
						container.BridgeSessions.add(candidateSession);
						candidateSession = ssh.getSession(forwardInfo.UserID, "127.0.0.1", port);
						candidateSession.setConfig("StrictHostKeyChecking", "no");
						candidateSession.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
						candidateSession.setPassword(forwardInfo.Password);
						candidateSession.connect(10 * 1000);
						forwardInfo = forwardInfo.PortForward;
					}
					Session session = candidateSession;
					container.Session = session;
					Channel channel = session.openChannel("shell");
					{
						ChannelShell shellChannel = (ChannelShell)channel;
						shellChannel.setPtyType("VT100");
						shellChannel.setPty(true);
					}
					channel.connect(10 * 1000);
					container.Channel = channel;
					VT100InputStream filter = new VT100InputStream(channel.getInputStream());
					container.Filter = filter;
					InputStreamReader streamReader = new InputStreamReader(filter, StandardCharsets.UTF_8);
					OutputStreamWriter streamWriter = new OutputStreamWriter(channel.getOutputStream(), StandardCharsets.UTF_8);
					BufferedReader reader = new BufferedReader(streamReader);
					container.Reader = reader;
					BufferedWriter writer = new BufferedWriter(streamWriter);
					container.Writer = writer;
					ExecutorService filterExecutor = Executors.newSingleThreadExecutor();
					CompletableFuture<?> filterTask = CompletableFuture.runAsync(filter, filterExecutor);
					filterExecutor.shutdown();
					ExecutorService readInputExecutor = Executors.newSingleThreadExecutor();
					CompletableFuture<?> readInputTask = CompletableFuture.runAsync(() -> {
						try {
							ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<String>();
							char[] buffer = new char[4096];
							int length;
							StringBuilder lineBuilder = new StringBuilder();
							while((length = reader.read(buffer)) != -1) {
								String readString = String.valueOf(buffer, 0, length);
								this.raiseReadString(readString, name);
								Platform.runLater(() -> {
									// テキストエリアは、\nで管理されているっぽい。\r\n放り込んでも\nになっちゃう。
									// getTextするのはコスト高そうだから、これで。
									if((profile.LimitOfLines != -1) && (lines.size() > profile.LimitOfLines)) {
										textArea.deleteText(0, lines.poll().length() + "\n".length());
									}
									String normalizedReadString = readString.replaceAll("\r\n?", "\n");
									textArea.appendText(normalizedReadString);
								});
								int offset = 0;
								while(offset < readString.length()) {
									int CRIndex = readString.indexOf("\r", offset);
									int LFIndex = readString.indexOf("\n", offset);
									int foundIndex = -1;
									int newlineSize = 0;
									if((CRIndex == -1) && (LFIndex == -1)) {
										// 改行は含まれていない。
									} else if((CRIndex != -1) && (LFIndex != -1)) {
										if(CRIndex + 1 == LFIndex) {
											// CRLFがある。
											foundIndex = CRIndex;
											newlineSize = 2;
										} else {
											if(CRIndex < LFIndex) {
												// CRがまずある。
												foundIndex = CRIndex;
												newlineSize = 1;
											} else /* if(CRIndex > LFIndex) */ {
												// LFがまずある。
												foundIndex = LFIndex;
												newlineSize = 1;
											}
										}
									} else if(CRIndex != -1) {
										// CRだけがある。
										foundIndex = CRIndex;
										newlineSize = 1;
									} else /* if(LFIndex != -1) */ {
										// LFだけがある。
										foundIndex = LFIndex;
										newlineSize = 1;
									}
									if(foundIndex != -1) {
										lineBuilder.append(readString.substring(offset, foundIndex));
										String line = lineBuilder.toString();
										lines.add(line);
										this.raiseReadLine(line, name);
										lineBuilder.setLength(0);
										offset = foundIndex + newlineSize;
									} else {
										// 改行は含まれていない。
										lineBuilder.append(readString.substring(offset));
										break;
									}
								}
							}
						} catch(IOException ex) {
							String message = ex.getMessage();
							if((message != null) && message.equals("Pipe closed")) {
								// ただ閉じられただけ。
							} else {
								ex.printStackTrace();
							}
						}
					}, readInputExecutor);
					readInputExecutor.shutdown();
					container.Task = CompletableFuture.allOf(filterTask, readInputTask).whenComplete((v, t) -> {
						try { filter.close(); } catch(IOException ex) {}
						channel.disconnect();
						session.disconnect();
						ArrayList<Session> bridgeSessions = container.BridgeSessions;
						if(bridgeSessions != null) {
							for(int i = bridgeSessions.size() - 1; i >= 0; --i) {
								bridgeSessions.get(i).disconnect();
							}
						}
						try { writer.close(); } catch(IOException ex) {}
						try { reader.close(); } catch(IOException ex) {}
						// タブ除去できるようにしても良いかも？
					});
					// 接続しながら、コマンド流すと混線するっぽい？（ひどい）。
					// たぶん、JSchあたりかInputStreamあたりの問題。
					if(profile.Commands != null) {
						for(Command command : profile.Commands) {
							String text = command.Text;
							ArrayList<Tab> tabs = new ArrayList<Tab>();
							tabs.add(tab);
							this.sendInputInternal(text, tabs);
						}
					}
				} catch(JSchException | IOException ex) {
					Platform.runLater(() -> {
						this.StatusLine.setText(MessageFormat.format("接続に失敗しました[{0}]。", ex.getMessage()));
					});
					ex.printStackTrace();
				}
			}
			Platform.runLater(() -> {
				this.StatusLine.setText("すべての接続が完了しました。");
			});
		}, connectExecutor);
		connectExecutor.shutdown();
	}

	private void raiseReadString(String readString, String name) throws IOException {
		for(IPlugin plugin : this.Plugins) {
			if(plugin.useStream()) {
				plugin.onReadString(name, readString);
			}
		}
	}

	private void raiseReadLine(String line, String name) throws IOException {
		for(IPlugin plugin : this.Plugins) {
			if(plugin.useStream()) {
				plugin.onReadLine(name, line);
			}
		}
	}

	/** 入力欄でアクションが行われたら、その内容を送る。 */
	@FXML
	public void Action_InputTextField() {
		this.sendInput();
	}
	/** 送るボタンが押されたら、入力内容を送る。 */
	@FXML
	public void Action_SendButton() {
		this.sendInput();
	}

	/** 入力されている内容を送ります。 */
	private void sendInput() {
		String text = this.InputTextField.getText();
		if(text.isEmpty()) {
			this.StatusLine.setText("入力がありません。");
			return;
		}
		boolean sendToAll = this.SendToAllToggleButton.isSelected();
		ArrayList<Tab> targetTabs = new ArrayList<Tab>();
		if(sendToAll) {
			targetTabs.addAll(this.ConsoleTabPane.getTabs());
		} else {
			Tab tab = this.ConsoleTabPane.getSelectionModel().getSelectedItem();
			if(tab != null) {
				targetTabs.add(tab);
			}
		}
		if(targetTabs.isEmpty()) {
			this.StatusLine.setText("対象のタブがありません。");
			return;
		}
		this.InputTextField.setText("");
		this.sendInputInternal(text, targetTabs);
	}

	private void sendInputInternal(String text, Collection<Tab> targetTabs) {
		boolean CR = this.CRToggleButton.isSelected();
		boolean LF = this.LFToggleButton.isSelected();
		this.Lock.lock();
		try {
			for(Tab tab : targetTabs) {
				SSHContainer container = this.SSHContainers.get(tab);
				try {
					Writer writer = container.Writer;
					writer.append(text + (CR ? "\r" : "") + (LF ? "\n" : ""));
					writer.flush();
				} catch(IOException ex) {
					ex.printStackTrace();
				}
			}
		} finally {
			this.Lock.unlock();
		}
	}

	/**
	 * ユーザーにメッセージを表示します。
	 */
	private void showMessage(String title, String message, Parent p) {
		Alert dialog = new Alert(AlertType.NONE);
		dialog.setTitle(title);
		dialog.setContentText(message);
		dialog.getButtonTypes().add(ButtonType.OK);
		Scene scene = dialog.getDialogPane().getScene();
		KeyCodeCombination kc = new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN);
		scene.getAccelerators().put(kc, () -> {
			HashMap<DataFormat, Object> m = new HashMap<DataFormat, Object>();
			String text = "" +
					"---------------------------\r\n" +
					title + "\r\n" +
					"---------------------------\r\n" +
					message + "\r\n" +
					"---------------------------\r\n" +
					ButtonType.OK.getText() + "\r\n" +
					"---------------------------\r\n";
			m.put(DataFormat.PLAIN_TEXT, text);
			Clipboard.getSystemClipboard().setContent(m);
		});
		dialog.setOnHidden((e) -> {
			scene.getAccelerators().remove(kc);
		});
		Window w = p.getScene().getWindow();
		dialog.initOwner(w);
		dialog.show();
	}

	@Override
	public void close() {
		for(IPlugin plugin : this.Plugins) {
			try {
				plugin.close();
			} catch(Exception ex) {
			}
		}
		this.Lock.lock();
		try {
			for(SSHContainer c : this.SSHContainers.values()) {
				try { c.Filter.close(); } catch(IOException ex) {}
				c.Channel.disconnect();
				c.Session.disconnect();
				ArrayList<Session> bridgeSessions = c.BridgeSessions;
				if(bridgeSessions != null) {
					for(int i = bridgeSessions.size() - 1; i >= 0; --i) {
						bridgeSessions.get(i).disconnect();
					}
				}
				try { c.Writer.close(); } catch(IOException ex) {}
				// チャネルを先に閉じないと、BufferdReader内部でsynchronizedしているのでハングする可能性がある。
				try { c.Reader.close(); } catch(IOException ex) {}
			}
		} finally {
			this.Lock.unlock();
		}
	}

}
