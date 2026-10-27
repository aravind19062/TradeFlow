-- DEMO DATA. Real ticker symbols are used for readability only: every price in
-- TradeFlow is produced by a seeded random walk and is NOT live market data.
INSERT INTO instruments (symbol, name, sector, asset_class, currency, base_price, volatility, drift) VALUES
('RELIANCE',   'Reliance Industries',          'Energy',                 'EQUITY', 'INR',  2850.00, 0.015, 0.0003),
('TCS',        'Tata Consultancy Services',    'Information Technology', 'EQUITY', 'INR',  3900.00, 0.012, 0.0002),
('INFY',       'Infosys',                      'Information Technology', 'EQUITY', 'INR',  1750.00, 0.014, 0.0002),
('HDFCBANK',   'HDFC Bank',                    'Financials',             'EQUITY', 'INR',  1650.00, 0.011, 0.0003),
('ICICIBANK',  'ICICI Bank',                   'Financials',             'EQUITY', 'INR',  1200.00, 0.013, 0.0004),
('SBIN',       'State Bank of India',          'Financials',             'EQUITY', 'INR',   820.00, 0.016, 0.0003),
('ITC',        'ITC',                          'Consumer Staples',       'EQUITY', 'INR',   460.00, 0.010, 0.0001),
('HINDUNILVR', 'Hindustan Unilever',           'Consumer Staples',       'EQUITY', 'INR',  2400.00, 0.009, 0.0001),
('LT',         'Larsen & Toubro',              'Industrials',            'EQUITY', 'INR',  3600.00, 0.014, 0.0003),
('BHARTIARTL', 'Bharti Airtel',                'Communication Services', 'EQUITY', 'INR',  1500.00, 0.013, 0.0004),
('SUNPHARMA',  'Sun Pharmaceutical',           'Health Care',            'EQUITY', 'INR',  1700.00, 0.012, 0.0002),
('MARUTI',     'Maruti Suzuki',                'Consumer Discretionary', 'EQUITY', 'INR', 12500.00, 0.013, 0.0002),
('NIFTYBEES',  'Nifty 50 Index ETF',           'Broad Market ETF',       'ETF',    'INR',   270.00, 0.008, 0.0002),
('GOLDBEES',   'Gold ETF',                     'Commodities',            'ETF',    'INR',    65.00, 0.007, 0.0001);

-- current_tick = -1 means "history not generated yet"; the service seeds it on first start.
INSERT INTO simulation_state (id, current_tick, updated_at) VALUES (1, -1, NULL);
