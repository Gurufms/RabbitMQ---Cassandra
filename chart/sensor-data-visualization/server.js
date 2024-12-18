const express = require('express');
const { Client } = require('cassandra-driver');
const path = require('path');
const moment = require('moment');
const cors = require('cors'); // Import cors

const app = express();
const PORT = process.env.PORT || 3000;

// Enable CORS for all routes
app.use(cors()); // Allow all origins (you can restrict this to specific origins if needed)

// Cassandra Client Connection
const client = new Client({
    contactPoints: ['localhost:9999'],
    localDataCenter: 'datacenter1',
    keyspace: 'rabbitmq'
});

// Middleware to serve static files
app.use(express.static(path.join(__dirname)));

// Route to fetch data from Cassandra
app.get('/api/data', async (req, res) => {
    console.log('Received request for /api/data');

    const sensors = ['sensor1', 'sensor2', 'sensor3', 'sensor4', 'sensor5']; // Define sensors to fetch

    try {
        // Create a promise for each sensor
        const promises = sensors.map(sensor => {
            const query = `
                SELECT current_time, sensor_value 
                FROM historical_data 
                WHERE sensor = ? 
                ALLOW FILTERING
            `;
            return client.execute(query, [sensor], { prepare: true })
                .then(result => {
                    return result.rows.map(row => ({
                        current_time: moment(row.current_time).toISOString(),
                        sensor_value: row.sensor_value,
                        sensor // Attach sensor identifier
                    }));
                });
        });

        // Wait for all promises to resolve
        const results = await Promise.all(promises);
        
        // Flatten the results array
        const sortedResults = [].concat(...results).sort((a, b) => new Date(b.current_time) - new Date(a.current_time));

        console.log(`Fetched ${sortedResults.length} records`);
        res.json(sortedResults);
    } catch (error) {
        console.error('Error fetching data from Cassandra:', error);
        res.status(500).json({ error: 'Error fetching data' });
    }
});

// Route to serve the index.html file
app.get('/', (req, res) => {
    console.log('Serving index.html'); // Log when the root route is hit
    res.sendFile(path.join(__dirname, 'index.html'));
});

// Start the server
app.listen(PORT, () => {
    console.log(`Server is running on http://localhost:${PORT}`);
});

// Handle process termination gracefully
process.on('SIGINT', async () => {
    console.log('Received SIGINT. Shutting down...');
    await client.shutdown();
    console.log('Cassandra client connection closed.');
    process.exit(0);
});
