package sinanmap.common.map;

/**
 * 地图显示的层。
 */
public enum LayerMode {

	/** 自动：在地表时画地表，走进地下自动切到洞穴层。 */
	AUTO("自动"),

	/** 始终画地表。 */
	SURFACE("地表"),

	/** 始终画洞穴层（玩家所在高度）。 */
	CAVE("洞穴");

	private final String displayName;

	LayerMode(String displayName) {
		this.displayName = displayName;
	}

	/** 界面上显示的名称。 */
	public String displayName() {
		return displayName;
	}
}
