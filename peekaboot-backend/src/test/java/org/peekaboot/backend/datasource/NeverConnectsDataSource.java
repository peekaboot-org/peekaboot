package org.peekaboot.backend.datasource;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** A DataSource that fails the test the moment anything asks it for a connection. */
final class NeverConnectsDataSource implements DataSource {

    @Override
    public Connection getConnection() {
        throw new AssertionError("connected");
    }

    @Override
    public Connection getConnection(String username, String password) {
        throw new AssertionError("connected");
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) {
        throw new AssertionError("unwrapped");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }
}
