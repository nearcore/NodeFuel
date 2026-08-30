#!/bin/bash

# Setup script for NodeFuel backend

echo "🔧 Installing NodeFuel backend..."

# Install Python dependencies
pip3 install -r requirements.txt

# Create config directory
mkdir -p ~/.config/nodefuel

# Copy example config
if [ ! -f ~/.config/nodefuel/config.json ]; then
    cp config.example.json ~/.config/nodefuel/config.json
    echo "⚠️  Config file created at ~/.config/nodefuel/config.json"
    echo "⚠️  Please edit it with your API keys!"
fi

# Create symlink for service script
mkdir -p ~/.local/bin
cp main.py ~/.local/bin/nodefuel-service
chmod +x ~/.local/bin/nodefuel-service

# Install systemd service
mkdir -p ~/.config/systemd/user
cp nodefuel.service ~/.config/systemd/user/
systemctl --user daemon-reload

echo "✅ Setup complete!"
echo "📝 Next steps:"
echo "   1. Edit ~/.config/nodefuel/config.json with your API keys"
echo "   2. Run: systemctl --user enable nodefuel"
echo "   3. Run: systemctl --user start nodefuel"
echo "   4. Check status: systemctl --user status nodefuel"
