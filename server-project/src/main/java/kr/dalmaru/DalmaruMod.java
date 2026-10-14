package kr.dalmaru;

import kr.dalmaru.network.DalmaruNetworking;
import net.fabricmc.api.ModInitializer;

public final class DalmaruMod implements ModInitializer {
	public static final String MOD_ID = "dalmaru";

	@Override
	public void onInitialize() {
        var content=kr.dalmaru.content.ContentCatalog.current();content.quest(kr.dalmaru.quest.QuestRepository.QUEST_ID);content.run("run.bongsudae");
		kr.dalmaru.account.VanillaCharacterState.initialize();
		DalmaruNetworking.initialize();
	}
}
