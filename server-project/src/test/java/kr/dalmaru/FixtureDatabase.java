package kr.dalmaru;

final class FixtureDatabase {
    private FixtureDatabase(){}
    static String databaseName() {
        String name=System.getenv("DALMARU_DB_NAME");
        if(name==null||!java.util.Set.of("oscar_p3_fixture","grok_p3_fixture").contains(name))throw new IllegalStateException("Explicit P3 test fixture required");
        return name;
    }
}
