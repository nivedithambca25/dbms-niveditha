CREATE DATABASE movie_db;
USE movie_db;

CREATE TABLE movies (
    movie_id INT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(100) NOT NULL,
    price DOUBLE NOT NULL
);

CREATE TABLE shows (
    show_id INT PRIMARY KEY AUTO_INCREMENT,
    movie_id INT,
    show_time VARCHAR(50),
    total_seats INT,
    available_seats INT,
    FOREIGN KEY (movie_id) REFERENCES movies(movie_id)
);

CREATE TABLE bookings (
    booking_id INT PRIMARY KEY AUTO_INCREMENT,
    customer_name VARCHAR(100),
    show_id INT,
    seats INT,
    total_amount DOUBLE,
    FOREIGN KEY (show_id) REFERENCES shows(show_id)
);

-- Sample data
INSERT INTO movies (title, price) VALUES
('Inception', 250.0),
('Interstellar', 300.0),
('The Dark Knight', 280.0);

INSERT INTO shows (movie_id, show_time, total_seats, available_seats) VALUES
(1, '10:00 AM', 100, 100),
(1, '06:00 PM', 100, 100),
(2, '02:00 PM', 80, 80),
(3, '09:00 PM', 120, 120);