package kr.dalmaru;

import java.util.UUID;
import kr.dalmaru.account.AccountRepository;

public final class AccountRepositoryCheck {
	private AccountRepositoryCheck() { }

	public static void main(String[] args) throws Exception {
		UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000002");
		AccountRepository repository = new AccountRepository();
		long firstGeneration = repository.openSession(accountId).connectionGeneration();
		long secondGeneration = repository.openSession(accountId).connectionGeneration();
		if (secondGeneration != firstGeneration + 1) {
			throw new IllegalStateException("Connection generation did not increment.");
		}
		System.out.println("Account session verified at generation " + secondGeneration);
	}
}
