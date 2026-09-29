package com.zengbohan.aurora.id.jdbc;

import com.zengbohan.aurora.id.SegmentLoader;
import com.zengbohan.aurora.id.SegmentLoader.Segment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MySQL-flavoured segment loader: UPDATE ... SET max_id = LAST_INSERT_ID(max_id + step)
 * makes the allocation and the read-back a single atomic statement per row.
 */
public class JdbcSegmentLoader implements SegmentLoader {

    private final DataSource dataSource;
    private final String table;

    public JdbcSegmentLoader(DataSource dataSource, String table) {
        this.dataSource = dataSource;
        this.table = table;
    }

    @Override
    public Segment next(String bizTag) {
        String update = "UPDATE " + table + " SET max_id = LAST_INSERT_ID(max_id + step) WHERE biz_tag = ?";
        String read = "SELECT LAST_INSERT_ID(), step FROM " + table + " WHERE biz_tag = ?";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement updateStatement = connection.prepareStatement(update)) {
                updateStatement.setString(1, bizTag);
                if (updateStatement.executeUpdate() != 1) {
                    throw new IllegalStateException("no segment row for bizTag " + bizTag);
                }
            }
            try (PreparedStatement readStatement = connection.prepareStatement(read)) {
                readStatement.setString(1, bizTag);
                try (ResultSet rs = readStatement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("segment row vanished for bizTag " + bizTag);
                    }
                    connection.commit();
                    return new Segment(rs.getLong(1), rs.getInt(2));
                }
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to allocate segment for " + bizTag, e);
        }
    }
}
