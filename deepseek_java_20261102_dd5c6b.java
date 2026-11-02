import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class MovieTicketWeb {

    static final String URL      = "jdbc:mysql://localhost:3306/movie_db";
    static final String USER     = "root";
    static final String PASSWORD = "your_password";   // <-- change this

    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", new HomeHandler());
        server.createContext("/book", new BookHandler());
        server.createContext("/cancel", new CancelHandler());
        server.createContext("/bookings", new BookingsHandler());
        server.setExecutor(null);
        server.start();

        System.out.println("Server started at http://localhost:8080");
    }

    // ---------- helpers ----------

    static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    static void sendHtml(HttpExchange ex, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static String layout(String title, String body) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><title>" + title + "</title>"
             + "<style>"
             + "body{font-family:Arial;margin:20px;background:#f5f5f5;}"
             + "h1{color:#c0392b;}"
             + "table{border-collapse:collapse;background:#fff;margin:10px 0;}"
             + "th,td{border:1px solid #ccc;padding:8px 14px;}"
             + "th{background:#2c3e50;color:#fff;}"
             + "a.btn,button{background:#27ae60;color:#fff;padding:8px 14px;text-decoration:none;border:none;cursor:pointer;border-radius:4px;}"
             + "input{padding:6px;margin:4px 0;}"
             + ".nav a{margin-right:16px;}"
             + "</style></head><body>"
             + "<h1>🎬 Movie Ticket Booking</h1>"
             + "<div class='nav'><a href='/'>Home</a><a href='/bookings'>All Bookings</a></div><hr>"
             + body
             + "</body></html>";
    }

    // ---------- handlers ----------

    static class HomeHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            StringBuilder html = new StringBuilder("<h2>Shows Available</h2><table>");
            html.append("<tr><th>Show ID</th><th>Movie</th><th>Time</th><th>Price</th>"
                      + "<th>Total Seats</th><th>Available</th><th>Action</th></tr>");
            try (Connection c = getConnection();
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                    "SELECT s.show_id, m.title, s.show_time, m.price, s.total_seats, s.available_seats " +
                    "FROM movies m JOIN shows s ON m.movie_id = s.movie_id ORDER BY s.show_id")) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    html.append("<tr>")
                        .append("<td>").append(id).append("</td>")
                        .append("<td>").append(rs.getString(2)).append("</td>")
                        .append("<td>").append(rs.getString(3)).append("</td>")
                        .append("<td>").append(rs.getDouble(4)).append("</td>")
                        .append("<td>").append(rs.getInt(5)).append("</td>")
                        .append("<td>").append(rs.getInt(6)).append("</td>")
                        .append("<td><a class='btn' href='/book?id=").append(id)
                        .append("'>Book</a></td>")
                        .append("</tr>");
                }
            } catch (Exception e) {
                html.append("<tr><td colspan='7'>Error: ").append(e.getMessage()).append("</td></tr>");
            }
            html.append("</table>");
            sendHtml(ex, layout("Home", html.toString()));
        }
    }

    static class BookHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            String query = ex.getRequestURI().getQuery();
            Map<String, String> params = parseForm(query);

            if ("GET".equalsIgnoreCase(method)) {
                String showId = params.getOrDefault("id", "");
                String body = "<h2>Book Ticket for Show ID " + showId + "</h2>"
                    + "<form method='POST' action='/book'>"
                    + "<input type='hidden' name='show_id' value='" + showId + "'>"
                    + "Name: <input name='name' required><br>"
                    + "Seats: <input name='seats' type='number' min='1' required><br>"
                    + "<button type='submit'>Confirm Booking</button>"
                    + "</form>";
                sendHtml(ex, layout("Book", body));
                return;
            }

            // POST
            String bodyStr = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = parseForm(bodyStr);
            String name = form.getOrDefault("name", "").trim();
            int showId = Integer.parseInt(form.get("show_id"));
            int seats = Integer.parseInt(form.get("seats"));

            String message;
            try (Connection c = getConnection()) {
                // Fetch price + available
                PreparedStatement ps = c.prepareStatement(
                    "SELECT m.price, s.available_seats FROM movies m " +
                    "JOIN shows s ON m.movie_id = s.movie_id WHERE s.show_id = ?");
                ps.setInt(1, showId);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) {
                    message = "❌ Invalid Show ID";
                } else {
                    double price = rs.getDouble(1);
                    int available = rs.getInt(2);
                    if (seats > available) {
                        message = "❌ Only " + available + " seats available";
                    } else {
                        double total = price * seats;
                        PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO bookings(customer_name, show_id, seats, total_amount) VALUES(?,?,?,?)",
                            Statement.RETURN_GENERATED_KEYS);
                        ins.setString(1, name);
                        ins.setInt(2, showId);
                        ins.setInt(3, seats);
                        ins.setDouble(4, total);
                        ins.executeUpdate();
                        ResultSet keys = ins.getGeneratedKeys();
                        int bookingId = keys.next() ? keys.getInt(1) : -1;

                        PreparedStatement upd = c.prepareStatement(
                            "UPDATE shows SET available_seats = available_seats - ? WHERE show_id = ?");
                        upd.setInt(1, seats);
                        upd.setInt(2, showId);
                        upd.executeUpdate();

                        message = "✅ Booking ID <b>" + bookingId + "</b> confirmed!<br>"
                                + "Seats: " + seats + "<br>Total: Rs. " + total;
                    }
                }
            } catch (Exception e) {
                message = "❌ Error: " + e.getMessage();
            }
            String body = "<h2>Booking Result</h2><p>" + message + "</p>"
                        + "<a class='btn' href='/'>Back to Home</a>";
            sendHtml(ex, layout("Result", body));
        }
    }

    static class BookingsHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            StringBuilder html = new StringBuilder("<h2>All Bookings</h2><table>");
            html.append("<tr><th>ID</th><th>Customer</th><th>Movie</th>"
                      + "<th>Time</th><th>Seats</th><th>Total</th><th>Action</th></tr>");
            try (Connection c = getConnection();
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                    "SELECT b.booking_id, b.customer_name, m.title, s.show_time, b.seats, b.total_amount " +
                    "FROM bookings b JOIN shows s ON b.show_id = s.show_id " +
                    "JOIN movies m ON s.movie_id = m.movie_id ORDER BY b.booking_id")) {
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    int id = rs.getInt(1);
                    html.append("<tr>")
                        .append("<td>").append(id).append("</td>")
                        .append("<td>").append(rs.getString(2)).append("</td>")
                        .append("<td>").append(rs.getString(3)).append("</td>")
                        .append("<td>").append(rs.getString(4)).append("</td>")
                        .append("<td>").append(rs.getInt(5)).append("</td>")
                        .append("<td>").append(rs.getDouble(6)).append("</td>")
                        .append("<td><a class='btn' href='/cancel?id=").append(id)
                        .append("'>Cancel</a></td></tr>");
                }
                if (!any) html.append("<tr><td colspan='7'>No bookings yet</td></tr>");
            } catch (Exception e) {
                html.append("<tr><td colspan='7'>Error: ").append(e.getMessage()).append("</td></tr>");
            }
            html.append("</table><br><a class='btn' href='/'>Back to Home</a>");
            sendHtml(ex, layout("Bookings", html.toString()));
        }
    }

    static class CancelHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            Map<String, String> params = parseForm(ex.getRequestURI().getQuery());
            int bookingId = Integer.parseInt(params.get("id"));
            String message;
            try (Connection c = getConnection()) {
                PreparedStatement ps = c.prepareStatement(
                    "SELECT show_id, seats FROM bookings WHERE booking_id = ?");
                ps.setInt(1, bookingId);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) {
                    message = "❌ Booking ID not found";
                } else {
                    int showId = rs.getInt(1);
                    int seats = rs.getInt(2);

                    PreparedStatement del = c.prepareStatement(
                        "DELETE FROM bookings WHERE booking_id = ?");
                    del.setInt(1, bookingId);
                    del.executeUpdate();

                    PreparedStatement upd = c.prepareStatement(
                        "UPDATE shows SET available_seats = available_seats + ? WHERE show_id = ?");
                    upd.setInt(1, seats);
                    upd.setInt(2, showId);
                    upd.executeUpdate();

                    message = "✅ Booking cancelled. " + seats + " seat(s) freed.";
                }
            } catch (Exception e) {
                message = "❌ Error: " + e.getMessage();
            }
            String body = "<h2>Cancel Result</h2><p>" + message + "</p>"
                        + "<a class='btn' href='/bookings'>Back to Bookings</a>";
            sendHtml(ex, layout("Cancel", body));
        }
    }
}