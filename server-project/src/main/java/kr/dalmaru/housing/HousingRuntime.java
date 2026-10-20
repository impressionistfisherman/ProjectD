package kr.dalmaru.housing;

import java.sql.SQLException;
import java.util.UUID;
import kr.dalmaru.request.RequestEnvelope;

/** Session view of a home. A new instance reads the stored home id after restart. */
public final class HousingRuntime {
	private final HousingRepository homes = new HousingRepository();

	public HousingRepository.Journaled createJournaled(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope) throws SQLException {
		return homes.createJournaled(account, generation, correlation, character, envelope);
	}

	public HousingRepository.Snapshot enter(UUID account, UUID character, UUID housingId, String returnDimension, int x, int y, int z) throws SQLException {
		return enter(account, character, housingId, returnDimension, x, y, z, false);
	}

	public HousingRepository.Snapshot enter(UUID account, UUID character, UUID housingId, String returnDimension, int x, int y, int z, boolean combat) throws SQLException {
		return homes.enter(account, character, housingId, returnDimension, x, y, z, combat);
	}

	public boolean claimBuild(UUID housingId) throws SQLException { return homes.claimBuild(housingId); }

	public HousingRepository.Snapshot leave(UUID account, UUID character) throws SQLException {
		return homes.leave(account, character);
	}

	public HousingRepository.Snapshot recover(UUID character) throws SQLException {
		return homes.recover(character);
	}
}
